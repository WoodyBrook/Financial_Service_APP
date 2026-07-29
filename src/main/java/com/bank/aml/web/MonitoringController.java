package com.bank.aml.web;

import com.bank.aml.service.MonitoringRunService;
import com.bank.aml.service.demo.DemoScenarioService;
import com.bank.aml.web.dto.MonitoringRunResponse;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MonitoringController {

    private final MonitoringRunService monitoringRunService;
    private final Optional<DemoScenarioService> demoScenarioService;

    /** Runs the sweep that is otherwise scheduled overnight, so it can be shown on demand. */
    @PostMapping("/api/monitoring/run")
    public MonitoringRunResponse run() {
        // While the live demo sits between Payment A and its scripted monitoring run, a
        // generic manual sweep would raise a case from the single weak signal — the demo
        // console owns this window instead.
        demoScenarioService.ifPresent(DemoScenarioService::assertNoScenarioInProgress);
        MonitoringRunService.MonitoringRunResult r = monitoringRunService.run(Instant.now());
        return new MonitoringRunResponse(
                r.asOf().toString(),
                r.customersEvaluated(),
                r.suppressedOpenCase(),
                r.casesRaised(),
                r.noActionRequired(),
                r.raisedCases().stream()
                        .map(c -> new MonitoringRunResponse.RaisedCase(
                                c.getId(), c.getCaseRef(), c.getCustomerId(),
                                c.getPriorityScore(), c.getPriorityBand(), c.getOpenedAt()))
                        .toList());
    }
}
