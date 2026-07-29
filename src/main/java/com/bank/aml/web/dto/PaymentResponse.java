package com.bank.aml.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * @param txnRef the reference the evidence trail will show — a presenter never has to hunt
 *               for an internal id
 * @param executedAt null while held: a held payment has not been executed
 * @param screeningOutcome NO_POTENTIAL_MATCH | POTENTIAL_MATCH
 * @param nameSimilarity 0-1 name similarity (not an identity probability); null on release
 */
public record PaymentResponse(
        String status,
        Long sanctionsHitId,
        Long paymentTxnId,
        String message,
        String txnRef,
        Instant executedAt,
        String screeningOutcome,
        BigDecimal nameSimilarity,
        String dateOfBirthMatch,
        String nationalityMatch) {}
