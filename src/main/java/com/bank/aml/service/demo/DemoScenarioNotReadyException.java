package com.bank.aml.service.demo;

/**
 * Raised when the scenario state row is absent — i.e. the demo data set has not been
 * reset into a presentation-ready state. Mapped to HTTP 404.
 */
public class DemoScenarioNotReadyException extends RuntimeException {

    public DemoScenarioNotReadyException(String message) {
        super(message);
    }
}
