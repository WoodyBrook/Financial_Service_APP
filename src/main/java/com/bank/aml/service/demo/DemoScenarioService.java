package com.bank.aml.service.demo;

import com.bank.aml.domain.CaseEntity;
import com.bank.aml.domain.CustomerEntity;
import com.bank.aml.domain.DemoScenarioStateEntity;
import com.bank.aml.domain.SanctionsHitEntity;
import com.bank.aml.domain.TransactionEntity;
import com.bank.aml.repo.AlertRepository;
import com.bank.aml.repo.CaseRepository;
import com.bank.aml.repo.CustomerRepository;
import com.bank.aml.repo.DemoScenarioStateRepository;
import com.bank.aml.repo.SanctionsHitRepository;
import com.bank.aml.repo.TransactionRepository;
import com.bank.aml.seed.AcmeScenarioFixture;
import com.bank.aml.seed.SeedDataRunner;
import com.bank.aml.seed.SyntheticTransactionFactory;
import com.bank.aml.service.FxRateService;
import com.bank.aml.service.MonitoringRunService;
import com.bank.aml.service.PaymentExecutionContext;
import com.bank.aml.service.SanctionsScreeningService;
import com.bank.aml.web.dto.PaymentRequest;
import com.bank.aml.web.dto.PaymentResponse;
import com.bank.aml.web.dto.demo.DemoScenarioStateResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the live demo scenario: reads the state row under lock, validates the step,
 * delegates to the real business services, verifies the invariants that make the story true,
 * then advances the step — all inside one transaction. It never re-implements sanctions,
 * rule or case logic; it only sequences calls and checks outcomes.
 *
 * <p>Repeating a completed action is idempotent (returns the recorded result); attempting an
 * action out of order is a 409 conflict.
 */
@Service
@Profile("demo")
@RequiredArgsConstructor
public class DemoScenarioService {

    public static final String STEP_READY = "READY";
    public static final String STEP_A_RELEASED = "PAYMENT_A_RELEASED";
    public static final String STEP_ACTIVITY_READY = "ACTIVITY_READY";
    public static final String STEP_CASE_RAISED = "CASE_RAISED";
    public static final String STEP_B_HELD = "PAYMENT_B_HELD";

    /** Every activity-row reference shares this prefix, so the invariant check and the
     *  summary are independent of any numbering scheme. Suffixes are the stable fixture
     *  offsets, so a retry after rollback can never collide with a unique constraint. */
    private static final String SCENARIO_TXN_PREFIX = "DEMO-ACME-";

    private final DemoScenarioStateRepository stateRepository;
    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final CaseRepository caseRepository;
    private final AlertRepository alertRepository;
    private final SanctionsHitRepository sanctionsHitRepository;
    private final SanctionsScreeningService sanctionsScreeningService;
    private final MonitoringRunService monitoringRunService;
    private final FxRateService fxRateService;
    private final SyntheticTransactionFactory transactionFactory;

    public static Instant paymentATime() {
        return SeedDataRunner.DEMO_DAY.minusDays(1).atTime(16, 52).toInstant(ZoneOffset.UTC);
    }

    public static Instant monitoringAsOf() {
        return SeedDataRunner.DEMO_DAY.atTime(2, 0).toInstant(ZoneOffset.UTC);
    }

    public static Instant activityWindowStart() {
        return SeedDataRunner.DEMO_DAY.minusDays(1).atTime(16, 10).toInstant(ZoneOffset.UTC);
    }

    @Transactional(readOnly = true)
    public DemoScenarioStateResponse currentState() {
        DemoScenarioStateEntity state = stateRepository
                .findById(DemoScenarioStateEntity.KEY)
                .orElseThrow(() -> new DemoScenarioNotReadyException(
                        "Demo scenario not initialised — run POST /api/demo/reset first"));
        return toResponse(state, null);
    }

    @Transactional
    public DemoScenarioStateResponse createPaymentA() {
        DemoScenarioStateEntity state = lockedState();
        if (STEP_A_RELEASED.equals(state.getStep()) && state.getAnchorPaymentId() != null) {
            return toResponse(state, null);
        }
        requireStep(state, STEP_READY, "create Payment A");

        CustomerEntity acme = acmeCustomer();
        AcmeScenarioFixture.ActivityTxn spec = AcmeScenarioFixture.PAYMENT_A;
        PaymentRequest request = new PaymentRequest(
                acme.getCustomerRef(), spec.counterpartyName(), spec.counterpartyCountry(),
                spec.amount(), spec.currency(), null, null);
        PaymentResponse response = sanctionsScreeningService.screenPayment(
                request, PaymentExecutionContext.virtualAt(paymentATime()));

        if (!"RELEASED".equals(response.status()) || response.executedAt() == null
                || !paymentATime().equals(response.executedAt())) {
            throw new DemoScenarioConflictException(
                    "DEMO_SCENARIO_INVARIANT_VIOLATION: Payment A must be RELEASED at the "
                            + "virtual business time, got status=" + response.status(),
                    state.getStep());
        }

        Instant asOf = monitoringAsOf();
        state.setCustomerId(acme.getId());
        state.setAnchorPaymentId(response.paymentTxnId());
        state.setSimulatedAsOf(asOf);
        advance(state, STEP_A_RELEASED);
        return toResponse(state, "NO_POTENTIAL_MATCH");
    }

    @Transactional
    public DemoScenarioStateResponse completeActivity() {
        DemoScenarioStateEntity state = lockedState();
        if (atLeast(state.getStep(), STEP_ACTIVITY_READY)) {
            return toResponse(state, null);
        }
        requireStep(state, STEP_A_RELEASED, "complete the day's activity");

        TransactionEntity anchor = transactionRepository.findById(state.getAnchorPaymentId())
                .orElseThrow(() -> new DemoScenarioConflictException(
                        "Anchor payment referenced by the scenario no longer exists", state.getStep()));
        CustomerEntity acme = customerRepository.findById(state.getCustomerId())
                .orElseThrow(() -> new DemoScenarioConflictException(
                        "Scenario customer no longer exists", state.getStep()));

        Instant windowStart = activityWindowStart();
        for (AcmeScenarioFixture.ActivityTxn spec : AcmeScenarioFixture.ACTIVITY) {
            if (spec.anchor()) {
                continue;
            }
            Instant when = windowStart.plus(spec.offsetMinutesFromWindowStart(), ChronoUnit.MINUTES);
            BigDecimal rate = fxRateService.rateToGbp(
                    spec.currency(), java.time.LocalDate.ofInstant(when, ZoneOffset.UTC));
            TransactionEntity t = transactionFactory.build(
                    SCENARIO_TXN_PREFIX + String.format("%02d", spec.offsetMinutesFromWindowStart()),
                    acme.getId(), acme.getCustomerRef(), spec.direction(), spec.amount(), spec.currency(),
                    rate, spec.counterpartyRef(), spec.counterpartyName(), spec.counterpartyCountry(), when);
            transactionRepository.save(t);
        }

        Set<Long> windowIds = new HashSet<>();
        for (TransactionEntity t : transactionRepository.findByCustomerIdAndExecutedAtBetween(
                acme.getId(), windowStart, state.getSimulatedAsOf())) {
            if (t.getId().equals(anchor.getId()) || t.getTxnRef().startsWith(SCENARIO_TXN_PREFIX)) {
                windowIds.add(t.getId());
            }
        }
        long anchorCount = windowIds.stream().filter(id -> id.equals(anchor.getId())).count();
        if (windowIds.size() != 8 || anchorCount != 1) {
            throw new DemoScenarioConflictException(
                    "DEMO_SCENARIO_INVARIANT_VIOLATION: expected exactly 8 scenario transactions "
                            + "with the anchor appearing once, found " + windowIds.size(),
                    state.getStep());
        }
        long supplementCount = transactionRepository.findByCustomerId(acme.getId()).stream()
                .filter(t -> AcmeScenarioFixture.BASELINE_COUNTERPARTIES.stream()
                        .anyMatch(cp -> cp[0].equals(t.getCounterpartyRef())))
                .filter(t -> t.getExecutedAt().isBefore(windowStart))
                .count();
        if (supplementCount != 15) {
            throw new DemoScenarioConflictException(
                    "DEMO_SCENARIO_INVARIANT_VIOLATION: ACME baseline supplement must remain 15 "
                            + "transactions, found " + supplementCount,
                    state.getStep());
        }

        advance(state, STEP_ACTIVITY_READY);
        return toResponse(state, null);
    }

    @Transactional
    public DemoScenarioStateResponse runMonitoring() {
        DemoScenarioStateEntity state = lockedState();
        if (atLeast(state.getStep(), STEP_CASE_RAISED)) {
            return toResponse(state, null);
        }
        requireStep(state, STEP_ACTIVITY_READY, "run overnight monitoring");

        MonitoringRunService.MonitoringRunResult result = monitoringRunService.run(state.getSimulatedAsOf());
        state.setMonitoringCustomersEvaluated(result.customersEvaluated());
        state.setMonitoringSuppressedOpenCase(result.suppressedOpenCase());
        state.setMonitoringCasesRaised(result.casesRaised());
        Long acmeId = state.getCustomerId();
        List<CaseEntity> acmeCases = result.raisedCases().stream()
                .filter(c -> acmeId.equals(c.getCustomerId()))
                .toList();
        if (result.casesRaised() != 1 || acmeCases.size() != 1) {
            throw new DemoScenarioConflictException(
                    "DEMO_SCENARIO_INVARIANT_VIOLATION: expected exactly one new case for ACME, "
                            + "got casesRaised=" + result.casesRaised(),
                    state.getStep());
        }
        CaseEntity caseEntity = acmeCases.get(0);
        int alertSum = alertRepository.findByCaseId(caseEntity.getId()).stream()
                .mapToInt(a -> a.getPoints()).sum();
        if (caseEntity.getPriorityScore() != alertSum || !"RED".equals(caseEntity.getPriorityBand())) {
            throw new DemoScenarioConflictException(
                    "DEMO_SCENARIO_INVARIANT_VIOLATION: case score must equal the sum of alert "
                            + "points and the band must be RED, got score=" + caseEntity.getPriorityScore()
                            + " band=" + caseEntity.getPriorityBand(),
                    state.getStep());
        }

        state.setCaseId(caseEntity.getId());
        advance(state, STEP_CASE_RAISED);
        return toResponse(state, null);
    }

    @Transactional
    public DemoScenarioStateResponse createPaymentB() {
        DemoScenarioStateEntity state = lockedState();
        if (STEP_B_HELD.equals(state.getStep()) && state.getSanctionsHitId() != null) {
            return toResponse(state, null);
        }
        requireStep(state, STEP_CASE_RAISED, "create Payment B");

        CustomerEntity acme = customerRepository.findById(state.getCustomerId())
                .orElseThrow(() -> new DemoScenarioConflictException(
                        "Scenario customer no longer exists", state.getStep()));
        PaymentRequest request = new PaymentRequest(
                acme.getCustomerRef(), "Vladmir Petrov", "RU",
                new BigDecimal("12500.00"), "GBP", "1971-03-02", "RU");
        PaymentResponse response = sanctionsScreeningService.screenPayment(request);

        if (!"HELD".equals(response.status()) || response.sanctionsHitId() == null) {
            throw new DemoScenarioConflictException(
                    "DEMO_SCENARIO_INVARIANT_VIOLATION: Payment B must be HELD with a sanctions "
                            + "hit, got status=" + response.status(),
                    state.getStep());
        }
        TransactionEntity txn = transactionRepository.findById(response.paymentTxnId())
                .orElseThrow(() -> new DemoScenarioConflictException(
                        "Payment B transaction missing after screening", state.getStep()));
        if (txn.getExecutedAt() != null) {
            throw new DemoScenarioConflictException(
                    "DEMO_SCENARIO_INVARIANT_VIOLATION: a held payment must not have an "
                            + "execution time", state.getStep());
        }
        SanctionsHitEntity hit = sanctionsHitRepository.findById(response.sanctionsHitId())
                .orElseThrow(() -> new DemoScenarioConflictException(
                        "Sanctions hit missing after screening", state.getStep()));
        var details = hit.getMatchDetailsSnapshot();
        if (!"EXACT".equals(details.path("dateOfBirth").path("match").asText())
                || !"EXACT".equals(details.path("nationality").path("match").asText())) {
            throw new DemoScenarioConflictException(
                    "DEMO_SCENARIO_INVARIANT_VIOLATION: DOB and nationality must both be EXACT",
                    state.getStep());
        }

        state.setSanctionsHitId(hit.getId());
        advance(state, STEP_B_HELD);
        return toResponse(state, null);
    }

    public void assertNoScenarioInProgress() {
        stateRepository.findById(DemoScenarioStateEntity.KEY).ifPresent(state -> {
            if (STEP_A_RELEASED.equals(state.getStep()) || STEP_ACTIVITY_READY.equals(state.getStep())) {
                throw new DemoScenarioConflictException(
                        "A demo scenario is between Payment A and its overnight monitoring run. "
                                + "Running the sweep now would raise a case from a single weak signal; "
                                + "use the demo console's Run monitoring step instead.",
                        state.getStep());
            }
        });
    }

    private DemoScenarioStateEntity lockedState() {
        return stateRepository.findWithLockByScenarioKey(DemoScenarioStateEntity.KEY)
                .orElseThrow(() -> new DemoScenarioNotReadyException(
                        "Demo scenario not initialised — run POST /api/demo/reset first"));
    }

    private void requireStep(DemoScenarioStateEntity state, String expected, String action) {
        if (!expected.equals(state.getStep())) {
            throw new DemoScenarioConflictException(
                    "Cannot " + action + " from step " + state.getStep() + "; expected " + expected,
                    state.getStep());
        }
    }

    private static boolean atLeast(String current, String step) {
        List<String> order = List.of(STEP_READY, STEP_A_RELEASED, STEP_ACTIVITY_READY, STEP_CASE_RAISED, STEP_B_HELD);
        return order.indexOf(current) >= order.indexOf(step);
    }

    private void advance(DemoScenarioStateEntity state, String nextStep) {
        state.setStep(nextStep);
        state.setUpdatedAt(Instant.now());
        stateRepository.save(state);
    }

    private CustomerEntity acmeCustomer() {
        return customerRepository.findByCustomerRef(AcmeScenarioFixture.CUSTOMER_REF)
                .orElseThrow(() -> new DemoScenarioNotReadyException(
                        "ACME customer missing — run POST /api/demo/reset first"));
    }

    private DemoScenarioStateResponse toResponse(DemoScenarioStateEntity state, String screeningOutcome) {
        String outcome = screeningOutcome;
        TransactionEntity anchor = state.getAnchorPaymentId() == null ? null
                : transactionRepository.findById(state.getAnchorPaymentId()).orElse(null);
        if (outcome == null && anchor != null) {
            outcome = "RELEASED".equals(anchor.getStatus()) ? "NO_POTENTIAL_MATCH" : "POTENTIAL_MATCH";
        }
        CaseEntity caseEntity = state.getCaseId() == null ? null
                : caseRepository.findById(state.getCaseId()).orElse(null);
        SanctionsHitEntity hit = state.getSanctionsHitId() == null ? null
                : sanctionsHitRepository.findById(state.getSanctionsHitId()).orElse(null);
        TransactionEntity heldTxn = hit != null && hit.getPaymentTxnId() != null
                ? transactionRepository.findById(hit.getPaymentTxnId()).orElse(null) : null;

        DemoScenarioStateResponse.AnchorPayment anchorPayment = anchor == null ? null
                : new DemoScenarioStateResponse.AnchorPayment(
                        anchor.getId(), anchor.getTxnRef(), anchor.getStatus(), anchor.getExecutedAt());

        DemoScenarioStateResponse.ActivitySummary activity = null;
        if (anchor != null && state.getSimulatedAsOf() != null && atLeast(state.getStep(), STEP_ACTIVITY_READY)) {
            List<TransactionEntity> window = transactionRepository
                    .findByCustomerIdAndExecutedAtBetween(
                            state.getCustomerId(), activityWindowStart(), state.getSimulatedAsOf())
                    .stream()
                    .filter(t -> t.getId().equals(anchor.getId())
                            || t.getTxnRef().startsWith(SCENARIO_TXN_PREFIX))
                    .toList();
            BigDecimal inbound = sum(window, "INBOUND");
            BigDecimal outbound = sum(window, "OUTBOUND");
            BigDecimal ratio = inbound.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : outbound.divide(inbound, 3, RoundingMode.HALF_UP);
            long newCps = window.stream()
                    .filter(t -> "OUTBOUND".equals(t.getDirection()))
                    .map(TransactionEntity::getCounterpartyRef)
                    .filter(ref -> ref != null && !ref.startsWith("CP-LEGACY-"))
                    .distinct().count();
            long minutes = window.stream().map(TransactionEntity::getExecutedAt)
                    .min(Instant::compareTo)
                    .map(first -> window.stream().map(TransactionEntity::getExecutedAt)
                            .max(Instant::compareTo)
                            .map(last -> ChronoUnit.MINUTES.between(first, last))
                            .orElse(0L))
                    .orElse(0L);
            activity = new DemoScenarioStateResponse.ActivitySummary(
                    window.size(), minutes, inbound, outbound, ratio, (int) newCps);
        }

        String focusTxnRef = null;
        if (caseEntity != null && anchor != null) {
            focusTxnRef = anchor.getTxnRef();
        }
        DemoScenarioStateResponse.RaisedCase raisedCase = caseEntity == null ? null
                : new DemoScenarioStateResponse.RaisedCase(
                        caseEntity.getId(), caseEntity.getCaseRef(), caseEntity.getPriorityScore(),
                        caseEntity.getPriorityBand(), focusTxnRef);

        DemoScenarioStateResponse.SanctionsHold hold = null;
        if (hit != null) {
            var details = hit.getMatchDetailsSnapshot();
            hold = new DemoScenarioStateResponse.SanctionsHold(
                    heldTxn != null ? heldTxn.getTxnRef() : null,
                    heldTxn != null ? heldTxn.getStatus() : "HELD",
                    null,
                    hit.getStatus(),
                    hit.getNameSimilarity(),
                    details.path("dateOfBirth").path("match").asText(null),
                    details.path("nationality").path("match").asText(null));
        }

        DemoScenarioStateResponse.MonitoringSummary monitoring =
                state.getMonitoringCustomersEvaluated() != null
                        ? new DemoScenarioStateResponse.MonitoringSummary(
                                state.getMonitoringCustomersEvaluated(),
                                state.getMonitoringSuppressedOpenCase(),
                                state.getMonitoringCasesRaised())
                        : null;
        return new DemoScenarioStateResponse(
                state.getStep(),
                customerRefOf(state),
                anchorPayment,
                outcome,
                activity,
                state.getSimulatedAsOf(),
                raisedCase,
                monitoring,
                hold,
                state.getVersion());
    }

    private String customerRefOf(DemoScenarioStateEntity state) {
        if (state.getCustomerId() == null) return AcmeScenarioFixture.CUSTOMER_REF;
        return customerRepository.findById(state.getCustomerId())
                .map(CustomerEntity::getCustomerRef)
                .orElse(AcmeScenarioFixture.CUSTOMER_REF);
    }

    private static BigDecimal sum(List<TransactionEntity> txns, String direction) {
        return txns.stream()
                .filter(t -> direction.equals(t.getDirection()))
                .map(TransactionEntity::getAmountGbp)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
