package com.bank.aml.service;

import com.bank.aml.config.AppProperties;
import com.bank.aml.domain.AiDraftEntity;
import com.bank.aml.domain.AlertEntity;
import com.bank.aml.domain.AuditEventEntity;
import com.bank.aml.domain.CaseEntity;
import com.bank.aml.domain.CustomerEntity;
import com.bank.aml.domain.TransactionEntity;
import com.bank.aml.repo.AiDraftRepository;
import com.bank.aml.repo.AlertRepository;
import com.bank.aml.repo.CaseRepository;
import com.bank.aml.repo.CustomerRepository;
import com.bank.aml.repo.TransactionRepository;
import com.bank.aml.web.dto.CaseDetailDto;
import com.bank.aml.web.dto.CaseListResponse;
import com.bank.aml.web.dto.CaseSummaryDto;
import com.bank.aml.web.dto.DispositionRequest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CaseService {
    private static final Set<String> VALID = Set.of("CLOSED_NFA", "CLOSED_FALSE_POSITIVE", "ESCALATED_INTERNAL");

    private final CaseRepository caseRepository;
    private final CustomerRepository customerRepository;
    private final AlertRepository alertRepository;
    private final TransactionRepository transactionRepository;
    private final AiDraftRepository aiDraftRepository;
    private final AuditService auditService;
    private final AppProperties appProperties;
    private final CountryInfoService countryInfoService;

    public CaseListResponse list(String status, String sort) {
        List<CaseEntity> cases = status == null || status.isBlank()
                ? caseRepository.findAll()
                : caseRepository.findByStatus(status);
        Instant now = Instant.now();
        String actor = appProperties.getActor();
        long overdue = cases.stream()
                .filter(c -> "OPEN".equals(c.getStatus()))
                .filter(c -> c.getSlaDueAt() != null && c.getSlaDueAt().isBefore(now))
                .count();
        long assigned = cases.stream()
                .filter(c -> "OPEN".equals(c.getStatus()))
                .filter(c -> actor.equals(c.getAssignedTo()))
                .count();
        long openCases = cases.stream().filter(c -> "OPEN".equals(c.getStatus())).count();

        Comparator<CaseEntity> cmp = "priority".equalsIgnoreCase(sort) || sort == null || sort.isBlank()
                ? Comparator.comparing(CaseEntity::getPriorityScore, Comparator.nullsLast(Comparator.reverseOrder()))
                : Comparator.comparing(CaseEntity::getOpenedAt, Comparator.nullsLast(Comparator.reverseOrder()));

        List<CaseSummaryDto> items = cases.stream()
                .sorted(cmp)
                .map(c -> {
                    CustomerEntity cust = customerRepository.findById(c.getCustomerId()).orElse(null);
                    return new CaseSummaryDto(
                            c.getId(),
                            c.getCaseRef(),
                            c.getCustomerId(),
                            cust != null ? cust.getCustomerRef() : null,
                            cust != null ? cust.getName() : null,
                            c.getPriorityScore(),
                            c.getPriorityBand(),
                            c.getStatus(),
                            c.getAssignedTo(),
                            c.getSlaDueAt(),
                            c.getOpenedAt(),
                            c.getSlaDueAt() != null && c.getSlaDueAt().isBefore(now) && "OPEN".equals(c.getStatus()),
                            alertRepository.findByCaseId(c.getId()).size());
                })
                .toList();

        CaseListResponse.Totals totals = new CaseListResponse.Totals(
                alertRepository.count(),
                openCases,
                assigned,
                overdue);
        return new CaseListResponse(totals, items);
    }

    @Transactional
    public CaseDetailDto get(Long id) {
        CaseEntity c = caseRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
        CustomerEntity customer = customerRepository.findById(c.getCustomerId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
        List<AlertEntity> alerts = alertRepository.findByCaseId(id);
        Instant ws = c.getWindowStart() != null ? c.getWindowStart() : c.getOpenedAt().minusSeconds(86400);
        Instant we = c.getWindowEnd() != null ? c.getWindowEnd() : c.getOpenedAt();
        List<TransactionEntity> timeline =
                transactionRepository.findByCustomerIdAndExecutedAtBetween(c.getCustomerId(), ws, we);
        AiDraftEntity draft = aiDraftRepository.findFirstByCaseIdOrderByGeneratedAtDesc(id).orElse(null);
        List<AuditEventEntity> audits = new java.util.ArrayList<>(auditService.forEntity("CASE", id));
        audits.addAll(auditService.forEntity("CUSTOMER", c.getCustomerId()));
        // AI draft events are recorded against the draft, but they belong in this case's trail —
        // the analyst's edit of the AI output is part of how this decision was reached.
        aiDraftRepository.findByCaseId(id)
                .forEach(d -> audits.addAll(auditService.forEntity("AI_DRAFT", d.getId())));
        audits.sort(Comparator.comparing(AuditEventEntity::getOccurredAt, Comparator.nullsLast(Comparator.reverseOrder())));
        auditService.record("CASE_VIEWED", "CASE", id, Map.of("caseRef", c.getCaseRef()));

        String focusTxnRef = null;
        for (AlertEntity alert : alerts) {
            if (!"R4".equals(alert.getRuleCode())) continue;
            com.fasterxml.jackson.databind.JsonNode ids = alert.getEvidenceSnapshot().path("transactionIds");
            if (!ids.isArray() || ids.isEmpty()) continue;
            TransactionEntity focus = transactionRepository.findById(ids.get(0).asLong()).orElse(null);
            if (focus != null) {
                focusTxnRef = focus.getTxnRef();
                break;
            }
        }

        Map<String, Object> customerMap = new HashMap<>();
        customerMap.put("id", customer.getId());
        customerMap.put("customerRef", customer.getCustomerRef());
        customerMap.put("name", customer.getName());
        customerMap.put("segment", customer.getSegment());
        customerMap.put("legalForm", customer.getLegalForm());
        customerMap.put("industry", customer.getIndustry());
        customerMap.put("incorporationCountry", customer.getIncorporationCountry());
        customerMap.put("incorporationCountryName", countryInfoService.displayName(customer.getIncorporationCountry()));
        customerMap.put("registrationDate", customer.getRegistrationDate());
        customerMap.put("crr", customer.getCrr());
        customerMap.put("monitoringStatus", customer.getMonitoringStatus());

        return new CaseDetailDto(c, customerMap, alerts, timeline, draft, audits, focusTxnRef);
    }

    @Transactional
    public CaseEntity dispose(Long id, DispositionRequest req) {
        if (req == null || req.rationale() == null || req.rationale().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rationale is required");
        }
        if (req.decision() == null || !VALID.contains(req.decision())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid decision");
        }
        CaseEntity c = caseRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
        if (!"OPEN".equals(c.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Case already disposed");
        }
        Instant now = Instant.now();
        c.setStatus(req.decision());
        c.setDispositionReason(req.rationale());
        c.setDisposedBy(appProperties.getActor());
        c.setDisposedAt(now);

        if ("ESCALATED_INTERNAL".equals(req.decision())) {
            c.setCrrReviewRequired(true);
            CustomerEntity customer = customerRepository.findById(c.getCustomerId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
            customer.setMonitoringStatus("PENDING_REVIEW");
            customerRepository.save(customer);
            caseRepository.save(c);
            auditService.record("CASE_ESCALATED", "CASE", id,
                    Map.of("decision", req.decision(), "rationale", req.rationale()));
            auditService.record("CUSTOMER_MONITORING_FLAGGED", "CUSTOMER", customer.getId(),
                    Map.of("monitoringStatus", "PENDING_REVIEW", "caseId", id));
            auditService.record("CRR_REVIEW_CREATED", "CUSTOMER", customer.getId(),
                    Map.of("caseId", id, "note", "CRR review required after escalation"));
        } else {
            caseRepository.save(c);
            auditService.record("CASE_DISPOSED", "CASE", id,
                    Map.of("decision", req.decision(), "rationale", req.rationale()));
        }
        return c;
    }
}
