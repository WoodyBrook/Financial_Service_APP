package com.bank.aml.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Explicit time source for the payment execution path. The formal API always uses
 * {@link #live()}; the demo scenario passes a virtual context for Payment A so the
 * transaction, FX date and payment audits all carry the same simulated business time —
 * never a post-hoc UPDATE of a row created with wall-clock time.
 */
public record PaymentExecutionContext(Instant occurredAt, LocalDate fxRateDate) {

    public static PaymentExecutionContext live() {
        Instant now = Instant.now();
        return new PaymentExecutionContext(now, LocalDate.ofInstant(now, ZoneOffset.UTC));
    }

    public static PaymentExecutionContext live(Clock clock) {
        Instant now = Instant.now(clock);
        return new PaymentExecutionContext(now, LocalDate.ofInstant(now, ZoneOffset.UTC));
    }

    public static PaymentExecutionContext virtualAt(Instant occurredAt) {
        return new PaymentExecutionContext(occurredAt, LocalDate.ofInstant(occurredAt, ZoneOffset.UTC));
    }
}
