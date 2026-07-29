package com.bank.aml.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Maps to table {@code case_record} because {@code case} is a reserved word in SQL.
 */
@Entity
@Table(name = "case_record")
@Getter
@Setter
public class CaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_ref", nullable = false, unique = true, length = 64)
    private String caseRef;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "priority_score", nullable = false)
    private Integer priorityScore;

    @Column(name = "priority_band", nullable = false, length = 16)
    private String priorityBand;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "crr_review_required", nullable = false)
    private Boolean crrReviewRequired;

    @Column(name = "prior_recent_cases", nullable = false)
    private Integer priorRecentCases;

    @Column(name = "assigned_to", length = 128)
    private String assignedTo;

    @Column(name = "sla_due_at")
    private Instant slaDueAt;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    @Column(name = "disposition_reason", columnDefinition = "text")
    private String dispositionReason;

    @Column(name = "disposed_by", length = 128)
    private String disposedBy;

    @Column(name = "disposed_at")
    private Instant disposedAt;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    /**
     * Not a mapped column: every case today is opened by a monitoring run, and the audit
     * trail records it as such. Exposed read-only so detail pages can state the provenance
     * without a schema change.
     */
    @com.fasterxml.jackson.annotation.JsonProperty("openedBy")
    public String getOpenedBy() {
        return "monitoring run";
    }
}
