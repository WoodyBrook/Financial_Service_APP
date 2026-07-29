package com.bank.aml.web.dto;

import java.time.Instant;
import java.util.List;

public record MonitoringRunResponse(
        String asOf,
        int customersEvaluated,
        int suppressedOpenCase,
        int casesRaised,
        int noActionRequired,
        List<RaisedCase> raisedCases) {

    public record RaisedCase(
            Long id,
            String caseRef,
            Long customerId,
            Integer priorityScore,
            String priorityBand,
            Instant openedAt) {}
}
