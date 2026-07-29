package com.bank.aml.seed;

import com.bank.aml.domain.TransactionEntity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * Builds a fully populated synthetic transaction from inputs the caller has already decided
 * (reference, FX snapshot, time, status). Pure construction only: no repository access and
 * no opinion about when a transaction should exist — Seed and the scenario service each
 * persist what they build here.
 */
@Component
public class SyntheticTransactionFactory {

    public TransactionEntity build(
            String txnRef,
            Long customerId,
            String customerRef,
            String direction,
            BigDecimal amount,
            String currency,
            BigDecimal fxRateUsed,
            String counterpartyRef,
            String counterpartyName,
            String counterpartyCountry,
            Instant occurredAt) {
        BigDecimal rate = fxRateUsed == null ? BigDecimal.ONE : fxRateUsed;
        BigDecimal gbp = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        TransactionEntity t = new TransactionEntity();
        t.setTxnRef(txnRef);
        t.setCustomerId(customerId);
        t.setAccountRef("ACC-" + customerRef);
        t.setDirection(direction);
        t.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        t.setCurrency(currency);
        t.setAmountGbp(gbp);
        t.setFxRateUsed(rate);
        t.setFxRateDate(LocalDate.ofInstant(occurredAt, ZoneOffset.UTC));
        t.setCounterpartyRef(counterpartyRef);
        t.setCounterpartyName(counterpartyName);
        t.setCounterpartyCountry(counterpartyCountry);
        t.setExecutedAt(occurredAt);
        t.setStatus("RELEASED");
        t.setCreatedAt(occurredAt);
        return t;
    }
}
