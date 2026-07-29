package com.bank.aml.service;

import com.bank.aml.domain.DemoScenarioStateEntity;
import com.bank.aml.repo.CustomerRepository;
import com.bank.aml.repo.DemoScenarioStateRepository;
import com.bank.aml.seed.DemoDataSeedService;
import com.bank.aml.seed.SeedDataRunner;
import com.bank.aml.service.demo.DemoScenarioService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Restores the demo to its opening state in one call: full truncate, deterministic reseed,
 * sequence reset, then a fresh READY scenario row. Truncating everything (rather than
 * targeted deletes) trades a few seconds for the guarantee that no rehearsal residue can
 * survive into the live run.
 */
@Service
@Profile("demo")
@RequiredArgsConstructor
@Slf4j
public class DemoResetService {

    /** Scenario state goes first — it holds foreign keys into the business tables. */
    private static final List<String> TABLES = List.of(
            "demo_scenario_state", "audit_event", "ai_draft", "sanctions_hit", "alert",
            "case_record", "txn", "sanctions_entry", "customer");

    private final EntityManager entityManager;
    private final SanctionsScreeningService sanctionsScreeningService;
    private final DemoDataSeedService demoDataSeedService;
    private final DemoScenarioStateRepository demoScenarioStateRepository;
    private final CustomerRepository customerRepository;

    @Transactional
    public Map<String, Object> reset() {
        for (String table : TABLES) {
            entityManager.createNativeQuery("TRUNCATE TABLE " + table + " RESTART IDENTITY CASCADE")
                    .executeUpdate();
        }
        entityManager.createNativeQuery("ALTER SEQUENCE payment_ref_seq RESTART WITH 1")
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        sanctionsScreeningService.ensureV1Loaded();
        Map<String, Long> counts = demoDataSeedService.resetToPresentationReady();

        Long acmeId = customerRepository.findByCustomerRef("CUS-000001")
                .map(c -> c.getId())
                .orElseThrow(() -> new IllegalStateException("ACME customer missing after seed"));
        DemoScenarioStateEntity state = new DemoScenarioStateEntity();
        state.setScenarioKey(DemoScenarioStateEntity.KEY);
        state.setStep(DemoScenarioService.STEP_READY);
        state.setCustomerId(acmeId);
        state.setSimulatedAsOf(DemoScenarioService.monitoringAsOf());
        state.setUpdatedAt(Instant.now());
        demoScenarioStateRepository.save(state);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "RESET");
        result.put("step", DemoScenarioService.STEP_READY);
        result.put("customerRef", "CUS-000001");
        result.put("acmeOpenCase", false);
        result.put("scenarioTransactionCount", 0);
        result.put("message", "Ready for Payment A");
        result.put("demoDay", SeedDataRunner.DEMO_DAY.toString());
        result.putAll(counts);
        log.info("Demo reset complete: {}", result);
        return result;
    }
}
