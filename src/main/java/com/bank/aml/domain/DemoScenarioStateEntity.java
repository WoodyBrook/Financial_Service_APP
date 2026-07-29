package com.bank.aml.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Single-row state machine for the live demo scenario. The scenario step is read and
 * advanced under a pessimistic lock; the {@code version} column additionally detects any
 * unexpected overwrite for diagnosis.
 */
@Entity
@Table(name = "demo_scenario_state")
@Getter
@Setter
public class DemoScenarioStateEntity {

    public static final String KEY = "live-demo";

    @Id
    @Column(name = "scenario_key", nullable = false, length = 64)
    private String scenarioKey;

    /** READY | PAYMENT_A_RELEASED | ACTIVITY_READY | CASE_RAISED | PAYMENT_B_HELD */
    @Column(name = "step", nullable = false, length = 32)
    private String step;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "anchor_payment_id")
    private Long anchorPaymentId;

    @Column(name = "simulated_as_of")
    private Instant simulatedAsOf;

    @Column(name = "case_id")
    private Long caseId;

    @Column(name = "sanctions_hit_id")
    private Long sanctionsHitId;

    @Column(name = "monitoring_customers_evaluated")
    private Integer monitoringCustomersEvaluated;

    @Column(name = "monitoring_suppressed_open_case")
    private Integer monitoringSuppressedOpenCase;

    @Column(name = "monitoring_cases_raised")
    private Integer monitoringCasesRaised;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
