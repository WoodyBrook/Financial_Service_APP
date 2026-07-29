package com.bank.aml.seed;

import java.math.BigDecimal;
import java.util.List;

/**
 * Pure data for the ACME live-demo story. This fixture deliberately contains no expected
 * rule codes, scores, bands or case counts — the rule engine is the only oracle, and tests
 * assert its output. Data only.
 */
public final class AcmeScenarioFixture {

    private AcmeScenarioFixture() {}

    public static final String CUSTOMER_REF = "CUS-000001";
    public static final String ACCOUNT_REF = "ACC-" + CUSTOMER_REF;

    /** Payment A, created live on stage through the real sanctions screening path. */
    public static final ActivityTxn PAYMENT_A = new ActivityTxn(
            "OUTBOUND", "GBP", new BigDecimal("26000.00"),
            "CP-NEW-04", "Sahel Logistics NG", "NG", 42, true);

    /** The complete anomalous window: three credits then five dispersals, 16:10 → 17:05. */
    public static final List<ActivityTxn> ACTIVITY = List.of(
            new ActivityTxn("INBOUND", "GBP", new BigDecimal("30000.00"),
                    "CP-LEGACY-A", "Rhine Distributors GmbH", "DE", 0, false),
            new ActivityTxn("INBOUND", "GBP", new BigDecimal("27200.00"),
                    "CP-LEGACY-B", "Seine Trade SA", "FR", 8, false),
            new ActivityTxn("INBOUND", "GBP", new BigDecimal("27000.00"),
                    "CP-LEGACY-C", "Baltic Merchants OU", "EE", 15, false),
            new ActivityTxn("OUTBOUND", "GBP", new BigDecimal("16000.00"),
                    "CP-NEW-01", "Cedar Ridge LLC", "US", 20, false),
            new ActivityTxn("OUTBOUND", "GBP", new BigDecimal("14500.00"),
                    "CP-NEW-02", "Orion Payments Ltd", "CY", 27, false),
            new ActivityTxn("OUTBOUND", "GBP", new BigDecimal("12000.00"),
                    "CP-NEW-03", "Horizon FX Desk", "LU", 34, false),
            PAYMENT_A,
            new ActivityTxn("OUTBOUND", "GBP", new BigDecimal("8964.00"),
                    "CP-LEGACY-A", "Rhine Distributors GmbH", "DE", 55, false));

    /** Long-standing history for the three payers, so R3 does not flag them as new. */
    public static final List<String[]> BASELINE_COUNTERPARTIES = List.of(
            new String[] {"CP-LEGACY-A", "Rhine Distributors GmbH", "DE"},
            new String[] {"CP-LEGACY-B", "Seine Trade SA", "FR"},
            new String[] {"CP-LEGACY-C", "Baltic Merchants OU", "EE"});

    /** 14, 28, 42, 56, 70 days back — 5 waves × 3 counterparties = 15 supplement txns. */
    public static final List<Integer> BASELINE_WAVE_DAYS_BACK = List.of(14, 28, 42, 56, 70);

    /**
     * @param anchor true for Payment A — it is created through formal screening, not by the
     *               activity step
     */
    public record ActivityTxn(
            String direction,
            String currency,
            BigDecimal amount,
            String counterpartyRef,
            String counterpartyName,
            String counterpartyCountry,
            int offsetMinutesFromWindowStart,
            boolean anchor) {}
}
