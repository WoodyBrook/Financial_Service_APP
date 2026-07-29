package com.bank.aml.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.aml.domain.AuditEventEntity;
import com.bank.aml.domain.CaseEntity;
import com.bank.aml.domain.SanctionsHitEntity;
import com.bank.aml.domain.TransactionEntity;
import com.bank.aml.repo.AuditEventRepository;
import com.bank.aml.repo.CaseRepository;
import com.bank.aml.repo.SanctionsHitRepository;
import com.bank.aml.repo.TransactionRepository;
import com.bank.aml.service.DemoResetService;
import com.bank.aml.service.demo.DemoScenarioConflictException;
import com.bank.aml.service.demo.DemoScenarioService;
import com.bank.aml.web.dto.demo.DemoScenarioStateResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Golden path plus the failure modes that would embarrass a presenter: out-of-order steps,
 * repeated clicks, and two browsers racing the same action.
 */
@SpringBootTest
@ActiveProfiles("demo")
class DemoScenarioIntegrationTest {

    @Autowired private DemoResetService demoResetService;
    @Autowired private DemoScenarioService scenarioService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private CaseRepository caseRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private SanctionsHitRepository sanctionsHitRepository;

    @BeforeEach
    void reset() {
        demoResetService.reset();
    }

    @Test
    @DisplayName("golden path: READY → A released → 8 txns → 1 RED case → B held")
    void goldenPath() {
        assertThat(scenarioService.currentState().step()).isEqualTo("READY");

        DemoScenarioStateResponse afterA = scenarioService.createPaymentA();
        assertThat(afterA.step()).isEqualTo("PAYMENT_A_RELEASED");
        assertThat(afterA.anchorPayment().status()).isEqualTo("RELEASED");
        assertThat(afterA.anchorPayment().executedAt())
                .isEqualTo(DemoScenarioService.paymentATime());
        assertThat(afterA.screeningOutcome()).isEqualTo("NO_POTENTIAL_MATCH");

        DemoScenarioStateResponse afterActivity = scenarioService.completeActivity();
        assertThat(afterActivity.step()).isEqualTo("ACTIVITY_READY");
        assertThat(afterActivity.activity().transactionCount()).isEqualTo(8);
        assertThat(afterActivity.activity().windowMinutes()).isEqualTo(55);

        DemoScenarioStateResponse afterMonitoring = scenarioService.runMonitoring();
        assertThat(afterMonitoring.step()).isEqualTo("CASE_RAISED");
        assertThat(afterMonitoring.raisedCase().priorityBand()).isEqualTo("RED");
        assertThat(afterMonitoring.raisedCase().focusTxnRef())
                .isEqualTo(afterA.anchorPayment().txnRef());

        DemoScenarioStateResponse afterB = scenarioService.createPaymentB();
        assertThat(afterB.step()).isEqualTo("PAYMENT_B_HELD");
        assertThat(afterB.sanctionsHold().status()).isEqualTo("HELD");
        assertThat(afterB.sanctionsHold().executedAt()).isNull();
        assertThat(afterB.sanctionsHold().outcome()).isEqualTo("POTENTIAL_MATCH");
        assertThat(afterB.sanctionsHold().dateOfBirthMatch()).isEqualTo("EXACT");
        assertThat(afterB.sanctionsHold().nationalityMatch()).isEqualTo("EXACT");
        // A held payment must never appear in an AML rule window.
        TransactionEntity held = transactionRepository
                .findByTxnRef(afterB.sanctionsHold().txnRef()).orElseThrow();
        assertThat(held.getExecutedAt()).isNull();
        SanctionsHitEntity hit = sanctionsHitRepository.findByCustomerId(held.getCustomerId())
                .stream()
                .filter(h -> held.getId().equals(h.getPaymentTxnId()))
                .findFirst().orElseThrow();
        assertThat(hit.getSanctionsEntrySnapshot().path("sourceUniqueId").asText())
                .isEqualTo("OS-DEMO-VLADIMIR-PETROV");
    }

    @Test
    @DisplayName("every action is idempotent: retries never duplicate business records")
    void actionsAreIdempotent() {
        scenarioService.createPaymentA();
        DemoScenarioStateResponse again = scenarioService.createPaymentA();
        long paymentsAfterRetry = transactionRepository.count();
        DemoScenarioStateResponse third = scenarioService.createPaymentA();
        assertThat(transactionRepository.count()).isEqualTo(paymentsAfterRetry);
        assertThat(third.anchorPayment().txnRef()).isEqualTo(again.anchorPayment().txnRef());

        scenarioService.completeActivity();
        long txns = transactionRepository.count();
        scenarioService.completeActivity();
        assertThat(transactionRepository.count()).isEqualTo(txns);

        scenarioService.runMonitoring();
        long cases = caseRepository.count();
        scenarioService.runMonitoring();
        assertThat(caseRepository.count()).isEqualTo(cases);

        scenarioService.createPaymentB();
        long txnsAfterB = transactionRepository.count();
        scenarioService.createPaymentB();
        assertThat(transactionRepository.count()).isEqualTo(txnsAfterB);
    }

    @Test
    @DisplayName("out-of-order actions are rejected with a conflict naming the real step")
    void outOfOrderIsConflict() {
        assertThatThrownBy(() -> scenarioService.completeActivity())
                .isInstanceOf(DemoScenarioConflictException.class)
                .hasMessageContaining("READY");
        assertThatThrownBy(() -> scenarioService.runMonitoring())
                .isInstanceOf(DemoScenarioConflictException.class);
        assertThatThrownBy(() -> scenarioService.createPaymentB())
                .isInstanceOf(DemoScenarioConflictException.class);

        scenarioService.createPaymentA();
        assertThatThrownBy(() -> scenarioService.runMonitoring())
                .isInstanceOf(DemoScenarioConflictException.class)
                .hasMessageContaining("PAYMENT_A_RELEASED");
        assertThatThrownBy(() -> scenarioService.createPaymentB())
                .isInstanceOf(DemoScenarioConflictException.class);
    }

    @Test
    @DisplayName("generic manual monitoring is guarded while the scenario owns the window")
    void genericMonitoringIsGuardedMidScenario() {
        scenarioService.createPaymentA();
        assertThatThrownBy(() -> scenarioService.assertNoScenarioInProgress())
                .isInstanceOf(DemoScenarioConflictException.class);
        scenarioService.completeActivity();
        assertThatThrownBy(() -> scenarioService.assertNoScenarioInProgress())
                .isInstanceOf(DemoScenarioConflictException.class);
        scenarioService.runMonitoring();
        // Once the case exists the scenario no longer needs exclusive control.
        scenarioService.assertNoScenarioInProgress();
    }

    @Test
    @DisplayName("two concurrent activity requests produce exactly one set of transactions")
    void concurrentActivityWritesOnce() throws Exception {
        scenarioService.createPaymentA();
        long before = transactionRepository.count();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Runnable task = () -> {
            try {
                start.await();
                scenarioService.completeActivity();
            } catch (Exception ignored) {
                // one of the two may serialise behind the lock; both outcomes must be legal
            }
        };
        Future<?> f1 = pool.submit(task);
        Future<?> f2 = pool.submit(task);
        start.countDown();
        f1.get(60, TimeUnit.SECONDS);
        f2.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(transactionRepository.count()).isEqualTo(before + 7);
        assertThat(scenarioService.currentState().step()).isEqualTo("ACTIVITY_READY");
    }

    @Test
    @DisplayName("two concurrent monitoring runs raise exactly one case")
    void concurrentMonitoringRaisesOnce() throws Exception {
        scenarioService.createPaymentA();
        scenarioService.completeActivity();
        long casesBefore = caseRepository.count();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Runnable task = () -> {
            try {
                start.await();
                scenarioService.runMonitoring();
            } catch (Exception ignored) {
            }
        };
        Future<?> f1 = pool.submit(task);
        Future<?> f2 = pool.submit(task);
        start.countDown();
        f1.get(60, TimeUnit.SECONDS);
        f2.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(caseRepository.count()).isEqualTo(casesBefore + 1);
    }

    @Test
    @DisplayName("business times are virtual; human times stay live")
    void auditAndRecordTimesRespectVirtualBusinessTime() {
        scenarioService.createPaymentA();
        Instant expected = DemoScenarioService.paymentATime();

        TransactionEntity anchor = transactionRepository
                .findByTxnRef(scenarioService.currentState().anchorPayment().txnRef()).orElseThrow();
        assertThat(anchor.getCreatedAt()).isEqualTo(expected);
        assertThat(anchor.getExecutedAt()).isEqualTo(expected);

        List<AuditEventEntity> paymentAudits = auditEventRepository
                .findByEntityTypeAndEntityIdOrderByOccurredAtDesc("CUSTOMER", anchor.getCustomerId());
        assertThat(paymentAudits)
                .filteredOn(a -> a.getAction().startsWith("PAYMENT_"))
                .allSatisfy(a -> assertThat(a.getOccurredAt()).isEqualTo(expected));

        scenarioService.completeActivity();
        scenarioService.runMonitoring();
        Instant asOf = DemoScenarioService.monitoringAsOf();
        CaseEntity raised = caseRepository
                .findById(scenarioService.currentState().raisedCase().id()).orElseThrow();
        assertThat(raised.getOpenedAt()).isEqualTo(asOf);
        List<AuditEventEntity> caseAudits = auditEventRepository
                .findByEntityTypeAndEntityIdOrderByOccurredAtDesc("CASE", raised.getId());
        assertThat(caseAudits)
                .filteredOn(a -> "CASE_OPENED".equals(a.getAction()))
                .allSatisfy(a -> assertThat(a.getOccurredAt()).isEqualTo(asOf));
        List<AuditEventEntity> runAudits = auditEventRepository
                .findByEntityTypeAndEntityIdOrderByOccurredAtDesc("MONITORING_RUN", 0L);
        assertThat(runAudits)
                .filteredOn(a -> "MONITORING_RUN_COMPLETED".equals(a.getAction()))
                .isNotEmpty()
                .allSatisfy(a -> assertThat(a.getOccurredAt()).isEqualTo(asOf));
    }

    @Test
    @DisplayName("reset forgets the old anchor, case and hit")
    void resetForgetsPreviousRun() {
        scenarioService.createPaymentA();
        scenarioService.completeActivity();
        scenarioService.runMonitoring();
        Long oldCaseId = scenarioService.currentState().raisedCase().id();

        demoResetService.reset();
        assertThat(scenarioService.currentState().step()).isEqualTo("READY");
        assertThat(scenarioService.currentState().raisedCase()).isNull();
        assertThat(scenarioService.currentState().anchorPayment()).isNull();
        assertThat(caseRepository.findById(oldCaseId)).isEmpty();
    }
}
