package com.bank.aml.web;

import com.bank.aml.service.demo.DemoScenarioService;
import com.bank.aml.web.dto.demo.DemoScenarioStateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo-only scenario API. Request bodies accept nothing: customer, references and times all
 * come from server state and the fixture, so the console cannot tamper with the story.
 */
@RestController
@Profile("demo")
@RequestMapping("/api/demo/scenario")
@RequiredArgsConstructor
public class DemoScenarioController {

    private final DemoScenarioService demoScenarioService;

    @GetMapping
    public DemoScenarioStateResponse state() {
        return demoScenarioService.currentState();
    }

    @PostMapping("/payment-a")
    public DemoScenarioStateResponse paymentA() {
        return demoScenarioService.createPaymentA();
    }

    @PostMapping("/activity")
    public DemoScenarioStateResponse activity() {
        return demoScenarioService.completeActivity();
    }

    @PostMapping("/run-monitoring")
    public DemoScenarioStateResponse runMonitoring() {
        return demoScenarioService.runMonitoring();
    }

    @PostMapping("/payment-b")
    public DemoScenarioStateResponse paymentB() {
        return demoScenarioService.createPaymentB();
    }
}
