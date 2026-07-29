package com.bank.aml.seed;

import com.bank.aml.config.AppProperties;
import com.bank.aml.domain.CustomerEntity;
import com.bank.aml.domain.TransactionEntity;
import com.bank.aml.repo.AlertRepository;
import com.bank.aml.repo.CaseRepository;
import com.bank.aml.repo.CustomerRepository;
import com.bank.aml.repo.TransactionRepository;
import com.bank.aml.service.CaseConsolidationService;
import com.bank.aml.service.FxRateService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic creation of everything the demo needs before the presenter walks on stage:
 * the generic 50-customer history, the ACME baseline supplement, and the pre-existing case
 * queue. It never advances the live scenario itself — the ACME anomalous window is created
 * on stage through the scenario API, not here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DemoDataSeedService {

    public static final long RANDOM_SEED = 42L;

    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final CaseRepository caseRepository;
    private final AlertRepository alertRepository;
    private final CaseConsolidationService caseConsolidationService;
    private final AppProperties appProperties;
    private final FxRateService fxRateService;
    private final SyntheticTransactionFactory transactionFactory;

    private final AtomicInteger txnSeq = new AtomicInteger(1000);
    private final Random random = new Random(RANDOM_SEED);

    /**
     * Builds the whole presentation-ready data set. Both generators are rewound first, so
     * every reset produces byte-identical numbers — otherwise the figures quoted on stage
     * would drift from the figures on screen.
     */
    @Transactional
    public Map<String, Long> resetToPresentationReady() {
        LocalDate demoDay = SeedDataRunner.DEMO_DAY;
        log.info("Seeding AML demo data for {}", demoDay);
        random.setSeed(RANDOM_SEED);
        txnSeq.set(1000);

        List<CustomerEntity> customers = seedCustomers();
        seedBaselineTransactions(customers, demoDay);
        CustomerEntity acme = customers.get(0);
        seedAcmeBaselineSupplement(acme, demoDay);
        seedExtraCases(customers, demoDay);

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("customers", customerRepository.count());
        counts.put("transactions", transactionRepository.count());
        counts.put("cases", caseRepository.count());
        counts.put("alerts", alertRepository.count());
        log.info("Seed complete: {}", counts);
        return counts;
    }

    private List<CustomerEntity> seedCustomers() {
        LocalDate demoDay = SeedDataRunner.DEMO_DAY;
        Instant demoNow = SeedDataRunner.DEMO_NOW;
        String[] industries = {"Trading", "Logistics", "Consultancy"};
        String[] forms = {"Ltd", "PLC"};
        String[] crrs = {"LOW", "LOW", "MEDIUM", "MEDIUM", "HIGH"};
        List<CustomerEntity> list = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            CustomerEntity c = new CustomerEntity();
            c.setCustomerRef(String.format("CUS-%06d", i));
            if (i == 1) {
                c.setName("ACME Trading Ltd");
                c.setIndustry("Trading");
                c.setLegalForm("Ltd");
                c.setCrr("MEDIUM");
            } else if (i == 50) {
                c.setName("Northern Peak Holdings Ltd");
                c.setIndustry("Consultancy");
                c.setLegalForm("Ltd");
                c.setCrr("LOW");
            } else {
                c.setName(companyName(i));
                c.setIndustry(industries[i % industries.length]);
                c.setLegalForm(forms[i % forms.length]);
                c.setCrr(crrs[i % crrs.length]);
            }
            c.setSegment("CORPORATE");
            c.setIncorporationCountry(i % 7 == 0 ? "IE" : "GB");
            c.setRegistrationDate(LocalDate.of(2010 + (i % 14), 1 + (i % 12), 1 + (i % 27)));
            c.setMonitoringStatus("NORMAL");
            c.setCreatedAt(demoNow.minus(400, ChronoUnit.DAYS));
            list.add(customerRepository.save(c));
        }
        return list;
    }

    private String companyName(int i) {
        String[] a = {"Bright", "Silver", "Harbour", "Atlas", "Summit", "Pacific", "Crown", "Vertex", "Northgate", "Oak"};
        String[] b = {"Goods", "Freight", "Partners", "Solutions", "Commerce", "Markets", "Advisory", "Exports"};
        return a[i % a.length] + " " + b[(i * 3) % b.length] + " " + (i % 2 == 0 ? "Ltd" : "PLC");
    }

    private void seedBaselineTransactions(List<CustomerEntity> customers, LocalDate demoDay) {
        for (CustomerEntity c : customers) {
            if (c.getCustomerRef().equals("CUS-000050")) {
                // dormant — almost no recent activity
                saveTxn(c, "INBOUND", bd(2500), "GBP", "CP-OLD-1", "Legacy Supplier", "DE",
                        demoDay.minusDays(120).atTime(11, 0).toInstant(ZoneOffset.UTC));
                continue;
            }
            int n = 25 + random.nextInt(20);
            for (int i = 0; i < n; i++) {
                int dayOffset = 2 + random.nextInt(58);
                Instant when = demoDay.minusDays(dayOffset).atTime(9 + random.nextInt(8), random.nextInt(60)).toInstant(ZoneOffset.UTC);
                boolean inbound = random.nextBoolean();
                double amt = Math.exp(8 + random.nextGaussian() * 0.6); // ~3k-10k typical
                if (c.getCustomerRef().equals(AcmeScenarioFixture.CUSTOMER_REF)) {
                    // ACME baseline daily inbound around ~10k median
                    amt = 8000 + random.nextDouble() * 4000;
                    inbound = random.nextDouble() < 0.55;
                }
                String ccy = switch (random.nextInt(3)) { case 0 -> "GBP"; case 1 -> "EUR"; default -> "USD"; };
                String cp = "CP-" + (1000 + random.nextInt(80));
                String country = switch (random.nextInt(5)) { case 0 -> "DE"; case 1 -> "FR"; case 2 -> "NL"; case 3 -> "US"; default -> "SG"; };
                saveTxn(c, inbound ? "INBOUND" : "OUTBOUND", bd(amt), ccy, cp, "Counterparty " + cp, country, when);
            }
        }
    }

    /**
     * Fifteen transactions across 14/28/42/56/70 days back give the three legacy payers real
     * history. Without it the rule engine would correctly, but misleadingly, flag them as
     * brand new on demo day. These must exist after every reset.
     */
    private void seedAcmeBaselineSupplement(CustomerEntity acme, LocalDate demoDay) {
        for (int d : AcmeScenarioFixture.BASELINE_WAVE_DAYS_BACK) {
            for (String[] cp : AcmeScenarioFixture.BASELINE_COUNTERPARTIES) {
                saveTxn(acme, "INBOUND", bd(9000 + random.nextInt(2500)), "GBP", cp[0], cp[1], cp[2],
                        demoDay.minusDays(d).atTime(10, 20).toInstant(ZoneOffset.UTC));
            }
        }
    }

    /** Behaviour patterns that trigger different subsets of R1-R4, so alert counts vary naturally. */
    private enum Profile { FULL_DISPERSAL, DISPERSAL_NO_HIGH_RISK, DISPERSAL_ONLY, AMOUNT_ONLY, NEW_BENEFICIARIES }

    private void seedExtraCases(List<CustomerEntity> customers, LocalDate demoDay) {
        // customerIndex -> behaviour. Scores are NEVER set here; whatever the rules produce is the truth.
        int[] idxs =        {2, 5, 8, 12, 18, 22, 27, 31, 35, 38, 41, 44, 46, 48};
        Profile[] profiles = {
            Profile.FULL_DISPERSAL, Profile.DISPERSAL_NO_HIGH_RISK, Profile.AMOUNT_ONLY,
            Profile.FULL_DISPERSAL, Profile.NEW_BENEFICIARIES, Profile.DISPERSAL_NO_HIGH_RISK,
            Profile.DISPERSAL_ONLY, Profile.AMOUNT_ONLY, Profile.NEW_BENEFICIARIES,
            Profile.DISPERSAL_ONLY, Profile.FULL_DISPERSAL, Profile.AMOUNT_ONLY,
            Profile.NEW_BENEFICIARIES, Profile.DISPERSAL_ONLY
        };
        for (int i = 0; i < idxs.length; i++) {
            CustomerEntity c = customers.get(idxs[i]);
            Instant asOf = demoDay.minusDays(1).atTime(8 + (i % 10), 15).toInstant(ZoneOffset.UTC);
            injectBehaviour(c, profiles[i], asOf.minus(2, ChronoUnit.HOURS), i);
            caseConsolidationService.evaluateAndConsolidate(c.getId(), asOf);
            // first two are past their SLA against the live clock, the rest are still in time
            assignAndSetSla(c, i < 2 ? -(2 + i) : (6 + i * 2));
        }

        // A closed case from three weeks ago, so prior_recent_cases is not always zero.
        // It goes through the rule engine like every other case — its score is earned, not typed in.
        CustomerEntity closedCust = customers.get(30);
        Instant closedAsOf = SeedDataRunner.DEMO_NOW.minus(20, ChronoUnit.DAYS);
        injectBehaviour(closedCust, Profile.DISPERSAL_NO_HIGH_RISK, closedAsOf.minus(2, ChronoUnit.HOURS), 99);
        caseConsolidationService.evaluateAndConsolidate(closedCust.getId(), closedAsOf)
                .ifPresent(closed -> {
                    closed.setStatus("CLOSED_NFA");
                    closed.setAssignedTo(appProperties.getActor());
                    closed.setOpenedAt(closedAsOf);
                    closed.setSlaDueAt(closedAsOf.plus(24, ChronoUnit.HOURS));
                    closed.setDisposedAt(closedAsOf.plus(2, ChronoUnit.DAYS));
                    closed.setDisposedBy(appProperties.getActor());
                    closed.setDispositionReason("Activity explained by seasonal inventory restocking; "
                            + "invoices and shipping documents obtained from the relationship manager.");
                    caseRepository.save(closed);
                });
    }

    /**
     * Never touches the score. The priority score is produced by the rule engine and must always
     * equal the sum of its alerts, otherwise the "every point is traceable" claim is false.
     */
    private void assignAndSetSla(CustomerEntity customer, long slaHours) {
        caseRepository.findByCustomerId(customer.getId()).stream()
                .filter(c -> "OPEN".equals(c.getStatus()))
                .findFirst()
                .ifPresent(open -> {
                    open.setAssignedTo(appProperties.getActor());
                    // SLA is measured against the live clock so the queue stays believable all week.
                    open.setSlaDueAt(slaHours >= 0
                            ? Instant.now().plus(slaHours, ChronoUnit.HOURS)
                            : Instant.now().minus(-slaHours, ChronoUnit.HOURS));
                    caseRepository.save(open);
                });
    }

    /**
     * Writes transactions only. Every alert and every point is then derived by the rule engine,
     * so nothing on screen is fabricated.
     */
    private void injectBehaviour(CustomerEntity c, Profile profile, Instant t0, int salt) {
        String tag = "S" + salt;
        switch (profile) {
            // Large inflow, dispersed fast to five new beneficiaries, one higher-risk -> R1 R2 R3 R4
            case FULL_DISPERSAL -> {
                saveTxn(c, "INBOUND", bd(17000 + salt * 400L), "GBP", "CP-" + tag + "-IN", "Meridian Supply Co", "DE", t0);
                saveTxn(c, "OUTBOUND", bd(4300), "GBP", "CP-" + tag + "-1", "Kestrel Partners", "US", t0.plus(12, ChronoUnit.MINUTES));
                saveTxn(c, "OUTBOUND", bd(3800), "GBP", "CP-" + tag + "-2", "Lumen Trade BV", "NL", t0.plus(21, ChronoUnit.MINUTES));
                saveTxn(c, "OUTBOUND", bd(3400), "GBP", "CP-" + tag + "-3", "Delta Consulting", "CY", t0.plus(29, ChronoUnit.MINUTES));
                saveTxn(c, "OUTBOUND", bd(2600), "GBP", "CP-" + tag + "-4", "Sahara Freight", "NG", t0.plus(40, ChronoUnit.MINUTES));
                saveTxn(c, "OUTBOUND", bd(1900), "GBP", "CP-" + tag + "-5", "Vale Holdings", "IE", t0.plus(52, ChronoUnit.MINUTES));
            }
            // Same shape but every beneficiary is in a low-risk country -> R1 R2 R3
            case DISPERSAL_NO_HIGH_RISK -> {
                saveTxn(c, "INBOUND", bd(28000 + salt * 600L), "GBP", "CP-" + tag + "-IN", "Halden Group", "SE", t0);
                saveTxn(c, "OUTBOUND", bd(9200), "GBP", "CP-" + tag + "-1", "Fjord Logistics", "NO", t0.plus(18, ChronoUnit.MINUTES));
                saveTxn(c, "OUTBOUND", bd(8100), "GBP", "CP-" + tag + "-2", "Alpine Components", "CH", t0.plus(31, ChronoUnit.MINUTES));
                saveTxn(c, "OUTBOUND", bd(6900), "GBP", "CP-" + tag + "-3", "Bruges Textiles", "BE", t0.plus(44, ChronoUnit.MINUTES));
            }
            // Inflow within the customer's normal range, but dispersed immediately -> R2 R3
            case DISPERSAL_ONLY -> {
                saveTxn(c, "INBOUND", bd(4800), "GBP", "CP-" + tag + "-IN", "Routine Payer Ltd", "FR", t0);
                saveTxn(c, "OUTBOUND", bd(1700), "GBP", "CP-" + tag + "-1", "Quill Services", "ES", t0.plus(15, ChronoUnit.MINUTES));
                saveTxn(c, "OUTBOUND", bd(1500), "GBP", "CP-" + tag + "-2", "Orchard Media", "PT", t0.plus(26, ChronoUnit.MINUTES));
                saveTxn(c, "OUTBOUND", bd(1300), "GBP", "CP-" + tag + "-3", "Bay Analytics", "IT", t0.plus(38, ChronoUnit.MINUTES));
            }
            // A single unusually large credit that simply sits there -> R1 only
            case AMOUNT_ONLY ->
                saveTxn(c, "INBOUND", bd(36000 + salt * 700L), "GBP", "CP-" + tag + "-IN", "Crestline Capital", "LU", t0);
            // Ordinary sums, but paid to beneficiaries never seen before -> R3 only
            case NEW_BENEFICIARIES -> {
                saveTxn(c, "OUTBOUND", bd(2600), "GBP", "CP-" + tag + "-1", "Novus Print Ltd", "GB", t0.plus(30, ChronoUnit.MINUTES));
                saveTxn(c, "OUTBOUND", bd(2100), "GBP", "CP-" + tag + "-2", "Ridge Facilities", "GB", t0.plus(95, ChronoUnit.MINUTES));
                saveTxn(c, "OUTBOUND", bd(1900), "GBP", "CP-" + tag + "-3", "Talbot Legal LLP", "GB", t0.plus(160, ChronoUnit.MINUTES));
            }
        }
    }

    private TransactionEntity saveTxn(
            CustomerEntity c, String direction, BigDecimal amount, String ccy,
            String cpRef, String cpName, String cpCountry, Instant when) {
        BigDecimal rate = "GBP".equals(ccy)
                ? BigDecimal.ONE
                : fxRateService.rateToGbp(ccy, LocalDate.ofInstant(when, ZoneOffset.UTC));
        TransactionEntity t = transactionFactory.build(
                String.format("TXN-%06d", txnSeq.getAndIncrement()),
                c.getId(), c.getCustomerRef(), direction, amount, ccy, rate,
                cpRef, cpName, cpCountry, when);
        return transactionRepository.save(t);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
