package com.bank.aml.service.demo;

/**
 * Raised when a scenario action is attempted from the wrong step, or when the business
 * data no longer matches the persisted scenario state. Mapped to HTTP 409 by
 * {@link com.bank.aml.web.ApiExceptionHandler}.
 */
public class DemoScenarioConflictException extends RuntimeException {

    private final String currentStep;

    public DemoScenarioConflictException(String message, String currentStep) {
        super(message);
        this.currentStep = currentStep;
    }

    public String getCurrentStep() {
        return currentStep;
    }
}
