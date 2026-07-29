package com.bank.aml.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.aml.domain.CaseEntity;
import com.bank.aml.domain.CustomerEntity;
import com.bank.aml.domain.TransactionEntity;
import com.bank.aml.repo.AlertRepository;
import com.bank.aml.repo.CaseRepository;
import com.bank.aml.repo.CustomerRepository;
import com.bank.aml.repo.TransactionRepository;
import com.bank.aml.seed.AcmeScenarioFixture;
import com.bank.aml.seed.SeedDataRunner;
import com.bank.aml.service.CaseConsolidationService;
import com.bank.aml.service.DemoResetService;
import com.bank.aml.service.RuleEngineService;
import com.bank.aml.service.demo.DemoScenarioService;
import com.bank.aml.service.rules.RuleHit;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Pins the business facts the live demo script depends on, before any structural change can
 * quietly move them. Every assertion runs against the real rule engine on the fixed seed —
 * nothing here is hand-computed.
 */
@SpringBootTest
@ActiveProfiles("demo")
class DemoBaselineCharacterizationTest {

    @Autowired private DemoResetService demoResetService;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private CaseRepository caseRepository;
    @Autowired private AlertRepository alertRepository;
    @Autowired private RuleEngineService ruleEngineService;
    @Autowired private CaseConsolidationService caseConsolidationService;

    @BeforeEach
    void reset() {
        demoResetService.reset();
    }

    private CustomerEntity acme() {
        return customerRepository.findByCustomerRef(AcmeScenarioFixture.CUSTOMER_REF).orElseThrow();
    }

    @Test
    @DisplayName("after reset ACME has no open case and no same-day anomalous activity")
    void acmeStartsClean() {
        CustomerEntity acme = acme();
        boolean open = caseRepository.findByCustomerId(acme.getId()).stream()
                .anyMatch(c -> "OPEN".equals(c.getStatus()));
        assertThat(open).as("ACME must not carry an open case after reset").isFalse();

        Instant windowStart = DemoScenarioService.activityWindowStart();
        Instant asOf = DemoScenarioService.monitoringAsOf();
        List<TransactionEntity> inWindow = transactionRepository
                .findByCustomerIdAndExecutedAtBetween(acme.getId(), windowStart, asOf);
        assertThat(inWindow).as("the anomalous window must be empty after reset").isEmpty();
    }

    @Test
    @DisplayName("ACME baseline supplement is exactly 15 transactions, all CP-LEGACY known")
    void acmeSupplementIsStable() {
        CustomerEntity acme = acme();
        Set<String> legacyRefs = AcmeScenarioFixture.BASELINE_COUNTERPARTIES.stream()
                .map(cp -> cp[0]).collect(Collectors.toSet());
        List<TransactionEntity> legacy = transactionRepository.findByCustomerId(acme.getId()).stream()
                .filter(t -> legacyRefs.contains(t.getCounterpartyRef()))
                .toList();
        assertThat(legacy).hasSize(15);
        assertThat(legacy).allSatisfy(t ->
                assertThat(t.getExecutedAt()).isBefore(DemoScenarioService.activityWindowStart()));
    }

    @Test
    @DisplayName("CP-NEW scenario counterparties never collide with the random baseline namespace")
    void counterpartyNamespacesAreDisjoint() {
        Set<String> scenarioRefs = AcmeScenarioFixture.ACTIVITY.stream()
                .map(AcmeScenarioFixture.ActivityTxn::counterpartyRef)
                .collect(Collectors.toSet());
        Set<String> legacyRefs = AcmeScenarioFixture.BASELINE_COUNTERPARTIES.stream()
                .map(cp -> cp[0]).collect(Collectors.toSet());
        scenarioRefs.addAll(legacyRefs);

        // Only random baseline refs — exclude the ACME supplement itself.
        List<String> randomRefs = transactionRepository.findAll().stream()
                .filter(t -> !legacyRefs.contains(t.getCounterpartyRef()))
                .map(TransactionEntity::getCounterpartyRef)
                .filter(ref -> ref != null && ref.startsWith("CP-"))
                .toList();
        assertThat(randomRefs).doesNotContainAnyElementsOf(scenarioRefs);
        assertThat(randomRefs).allSatisfy(ref -> {
            if (ref.startsWith("CP-NEW") || ref.startsWith("CP-LEGACY")) {
                throw new AssertionError("baseline produced scenario-namespace ref " + ref);
            }
        });
    }

    @Test
    @DisplayName("customers without seeded behaviour produce no rule hits in the demo window")
    void quietCustomersStayQuiet() {
        Instant asOf = DemoScenarioService.monitoringAsOf();
        // CUS-000004 receives only the generic random baseline (index 3 is not in the
        // seedExtraCases idxs array, so it has no injected behaviour).
        CustomerEntity quiet = customerRepository.findByCustomerRef("CUS-000004").orElseThrow();
        List<RuleHit> hits = ruleEngineService.evaluate(quiet.getId(), asOf);
        assertThat(hits).as("a quiet customer must not alert in the demo window").isEmpty();
    }

    @Test
    @DisplayName("Payment A alone is exactly one weak signal: R4 only, 15 points, GREEN")
    void paymentAAloneIsOnlyR4() {
        CustomerEntity acme = acme();
        Instant windowStart = DemoScenarioService.activityWindowStart();
        AcmeScenarioFixture.ActivityTxn spec = AcmeScenarioFixture.PAYMENT_A;

        TransactionEntity t = new TransactionEntity();
        t.setTxnRef("CHAR-PA-ONLY");
        t.setCustomerId(acme.getId());
        t.setAccountRef("ACC-" + acme.getCustomerRef());
        t.setDirection(spec.direction());
        t.setAmount(spec.amount());
        t.setCurrency(spec.currency());
        t.setAmountGbp(spec.amount());
        t.setFxRateUsed(java.math.BigDecimal.ONE);
        t.setFxRateDate(SeedDataRunner.DEMO_DAY.minusDays(1));
        t.setCounterpartyRef(spec.counterpartyRef());
        t.setCounterpartyName(spec.counterpartyName());
        t.setCounterpartyCountry(spec.counterpartyCountry());
        t.setExecutedAt(DemoScenarioService.paymentATime());
        t.setStatus("RELEASED");
        t.setCreatedAt(DemoScenarioService.paymentATime());
        transactionRepository.save(t);

        try {
            Instant asOf = DemoScenarioService.monitoringAsOf();
            List<RuleHit> hits = ruleEngineService.evaluate(acme.getId(), asOf);
            assertThat(hits).extracting(RuleHit::ruleCode).containsExactly("R4");
            assertThat(hits.get(0).points()).isEqualTo(15);
            assertThat(CaseConsolidationService.band(hits.get(0).points())).isEqualTo("GREEN");
        } finally {
            transactionRepository.delete(t);
        }
    }

    @Test
    @DisplayName("the full activity window hits R1–R4 and scores RED with points summing exactly")
    void fullActivityIsRedAndTraceable() {
        CustomerEntity acme = acme();
        Instant windowStart = DemoScenarioService.activityWindowStart();
        java.util.List<TransactionEntity> created = new java.util.ArrayList<>();
        for (AcmeScenarioFixture.ActivityTxn spec : AcmeScenarioFixture.ACTIVITY) {
            TransactionEntity t = new TransactionEntity();
            t.setTxnRef("CHAR-ACT-" + spec.offsetMinutesFromWindowStart());
            t.setCustomerId(acme.getId());
            t.setAccountRef("ACC-" + acme.getCustomerRef());
            t.setDirection(spec.direction());
            t.setAmount(spec.amount());
            t.setCurrency(spec.currency());
            t.setAmountGbp(spec.amount());
            t.setFxRateUsed(java.math.BigDecimal.ONE);
            t.setFxRateDate(SeedDataRunner.DEMO_DAY.minusDays(1));
            t.setCounterpartyRef(spec.counterpartyRef());
            t.setCounterpartyName(spec.counterpartyName());
            t.setCounterpartyCountry(spec.counterpartyCountry());
            t.setExecutedAt(windowStart.plus(spec.offsetMinutesFromWindowStart(), java.time.temporal.ChronoUnit.MINUTES));
            t.setStatus("RELEASED");
            t.setCreatedAt(t.getExecutedAt());
            created.add(transactionRepository.save(t));
        }

        try {
            Instant asOf = DemoScenarioService.monitoringAsOf();
            List<RuleHit> hits = ruleEngineService.evaluate(acme.getId(), asOf);
            assertThat(hits).extracting(RuleHit::ruleCode)
                    .containsExactlyInAnyOrder("R1", "R2", "R3", "R4");
            int score = hits.stream().mapToInt(RuleHit::points).sum();
            assertThat(CaseConsolidationService.band(score)).isEqualTo("RED");

            var caseEntity = caseConsolidationService.evaluateAndConsolidate(acme.getId(), asOf).orElseThrow();
            int alertSum = alertRepository.findByCaseId(caseEntity.getId()).stream()
                    .mapToInt(a -> a.getPoints()).sum();
            assertThat(caseEntity.getPriorityScore()).isEqualTo(alertSum);
            assertThat(caseEntity.getPriorityBand()).isEqualTo("RED");
            assertThat(caseEntity.getOpenedAt()).isEqualTo(asOf);
        } finally {
            caseRepository.findByCustomerId(acme.getId()).forEach(c -> {
                alertRepository.findByCaseId(c.getId()).forEach(alertRepository::delete);
                caseRepository.delete(c);
            });
            transactionRepository.deleteAll(created);
        }
    }

    @Test
    @DisplayName("reset response advertises the READY scenario contract")
    void resetAdvertisesReadyState() {
        Map<String, Object> result = demoResetService.reset();
        assertThat(result.get("step")).isEqualTo("READY");
        assertThat(result.get("customerRef")).isEqualTo("CUS-000001");
        assertThat(result.get("acmeOpenCase")).isEqualTo(false);
        assertThat(result.get("scenarioTransactionCount")).isEqualTo(0);
    }
}
