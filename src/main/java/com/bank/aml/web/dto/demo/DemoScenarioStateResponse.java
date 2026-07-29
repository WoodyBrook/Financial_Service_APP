package com.bank.aml.web.dto.demo;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Server-authoritative scenario state. The presenter console renders exactly this and
 * nothing else — no client-side computation of scores, references or metrics.
 */
public record DemoScenarioStateResponse(
        String step,
        String customerRef,
        AnchorPayment anchorPayment,
        String screeningOutcome,
        ActivitySummary activity,
        Instant simulatedAsOf,
        RaisedCase raisedCase,
        MonitoringSummary monitoring,
        SanctionsHold sanctionsHold,
        Long version) {

    public record AnchorPayment(Long id, String txnRef, String status, Instant executedAt) {}

    public record ActivitySummary(
            int transactionCount,
            long windowMinutes,
            BigDecimal inboundGbp,
            BigDecimal outboundGbp,
            BigDecimal dispersalRatio,
            int newCounterpartyCount) {}

    public record RaisedCase(
            Long id, String caseRef, Integer priorityScore, String priorityBand, String focusTxnRef) {}

    public record MonitoringSummary(
            int customersEvaluated, int suppressedOpenCase, int casesRaised) {}

    public record SanctionsHold(
            String txnRef,
            String status,
            Instant executedAt,
            String outcome,
            BigDecimal nameSimilarity,
            String dateOfBirthMatch,
            String nationalityMatch) {}
}
