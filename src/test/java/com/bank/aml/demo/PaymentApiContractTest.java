package com.bank.aml.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.aml.repo.TransactionRepository;
import com.bank.aml.service.DemoResetService;
import com.bank.aml.service.SanctionsScreeningService;
import com.bank.aml.web.dto.PaymentRequest;
import com.bank.aml.web.dto.PaymentResponse;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The formal payment API gained fields and a sequence-backed reference; both must hold
 * for ordinary (non-scenario) callers too.
 */
@SpringBootTest
@ActiveProfiles("demo")
class PaymentApiContractTest {

    @Autowired private DemoResetService demoResetService;
    @Autowired private SanctionsScreeningService sanctionsScreeningService;
    @Autowired private TransactionRepository transactionRepository;

    @BeforeEach
    void reset() {
        demoResetService.reset();
    }

    @Test
    @DisplayName("a clean payment is released with txnRef, executedAt and an explicit outcome")
    void releasedPaymentCarriesFullContract() {
        PaymentResponse res = sanctionsScreeningService.screenPayment(new PaymentRequest(
                "CUS-000001", "Routine Trading Partner", "DE",
                new BigDecimal("1000.00"), "GBP", null, null));
        assertThat(res.status()).isEqualTo("RELEASED");
        assertThat(res.txnRef()).startsWith("PAY-");
        assertThat(res.executedAt()).isNotNull();
        assertThat(res.screeningOutcome()).isEqualTo("NO_POTENTIAL_MATCH");
        assertThat(res.nameSimilarity()).isNull();
        assertThat(res.sanctionsHitId()).isNull();
    }

    @Test
    @DisplayName("a listed beneficiary is held with similarity and EXACT attribute evidence")
    void heldPaymentCarriesMatchEvidence() {
        PaymentResponse res = sanctionsScreeningService.screenPayment(new PaymentRequest(
                "CUS-000002", "Vladmir Petrov", "RU",
                new BigDecimal("12500.00"), "GBP", "1971-03-02", "RU"));
        assertThat(res.status()).isEqualTo("HELD");
        assertThat(res.executedAt()).isNull();
        assertThat(res.screeningOutcome()).isEqualTo("POTENTIAL_MATCH");
        assertThat(res.nameSimilarity()).isNotNull();
        assertThat(res.nameSimilarity().doubleValue()).isGreaterThanOrEqualTo(0.85);
        assertThat(res.dateOfBirthMatch()).isEqualTo("EXACT");
        assertThat(res.nationalityMatch()).isEqualTo("EXACT");
        assertThat(res.sanctionsHitId()).isNotNull();
        // Held means not executed: the payment must stay out of every AML rule window.
        assertThat(transactionRepository.findById(res.paymentTxnId()).orElseThrow().getExecutedAt())
                .isNull();
    }

    @Test
    @DisplayName("payment references come from the sequence: unique and monotonic, never count-derived")
    void paymentReferencesAreSequenceBacked() {
        Set<String> refs = new HashSet<>();
        int max = -1;
        for (int i = 0; i < 3; i++) {
            PaymentResponse res = sanctionsScreeningService.screenPayment(new PaymentRequest(
                    "CUS-000001", "Routine Trading Partner", "DE",
                    new BigDecimal("100.00"), "GBP", null, null));
            assertThat(refs.add(res.txnRef())).as("duplicate payment reference %s", res.txnRef()).isTrue();
            int seq = Integer.parseInt(res.txnRef().substring("PAY-".length()));
            assertThat(seq).isGreaterThan(max);
            max = seq;
        }
        // Sequence-based references are independent of how many transactions already exist:
        // after a reset the sequence restarts, and the first payment is PAY-000001.
        demoResetService.reset();
        PaymentResponse first = sanctionsScreeningService.screenPayment(new PaymentRequest(
                "CUS-000001", "Routine Trading Partner", "DE",
                new BigDecimal("50.00"), "GBP", null, null));
        assertThat(first.txnRef()).isEqualTo("PAY-000001");
    }
}
