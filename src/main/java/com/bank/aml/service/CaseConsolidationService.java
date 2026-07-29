package com.bank.aml.service;

import com.bank.aml.config.AppProperties;
import com.bank.aml.domain.AlertEntity;
import com.bank.aml.domain.CaseEntity;
import com.bank.aml.repo.AlertRepository;
import com.bank.aml.repo.CaseRepository;
import com.bank.aml.service.rules.RuleHit;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CaseConsolidationService {
    private final RuleEngineService ruleEngineService;
    private final AlertRepository alertRepository;
    private final CaseRepository caseRepository;
    private final AuditService auditService;
    private final AppProperties appProperties;

    @Transactional
    public Optional<CaseEntity> evaluateAndConsolidate(Long customerId, Instant asOf) {
        List<RuleHit> hits = ruleEngineService.evaluate(customerId, asOf);
        if (hits.isEmpty()) return Optional.empty();

        Optional<CaseEntity> openOpt = caseRepository.findByCustomerId(customerId).stream()
                .filter(c -> "OPEN".equals(c.getStatus()))
                .findFirst();

        CaseEntity caseEntity;
        boolean created = false;
        if (openOpt.isPresent()) {
            caseEntity = openOpt.get();
        } else {
            caseEntity = new CaseEntity();
            caseEntity.setCustomerId(customerId);
            caseEntity.setStatus("OPEN");
            caseEntity.setCrrReviewRequired(false);
            caseEntity.setAssignedTo(appProperties.getActor());
            caseEntity.setOpenedAt(asOf);
            caseEntity.setSlaDueAt(asOf.plus(24, ChronoUnit.HOURS));
            caseEntity.setWindowStart(asOf.minus(24, ChronoUnit.HOURS));
            caseEntity.setWindowEnd(asOf);
            Instant since = asOf.minus(90, ChronoUnit.DAYS);
            int prior = (int) caseRepository.findByCustomerId(customerId).stream()
                    .filter(c -> c.getStatus() != null && c.getStatus().startsWith("CLOSED"))
                    .filter(c -> c.getDisposedAt() != null && !c.getDisposedAt().isBefore(since))
                    .count();
            caseEntity.setPriorRecentCases(prior);
            caseEntity.setPriorityScore(0);
            caseEntity.setPriorityBand("GREEN");
            caseEntity.setCaseRef(nextCaseRef());
            caseEntity = caseRepository.save(caseEntity);
            created = true;
        }

        Map<String, AlertEntity> bestByRule = new HashMap<>();
        for (AlertEntity existing : alertRepository.findByCaseId(caseEntity.getId())) {
            bestByRule.put(existing.getRuleCode(), existing);
        }

        for (RuleHit hit : hits) {
            AlertEntity current = bestByRule.get(hit.ruleCode());
            if (current != null && current.getStrength().compareTo(hit.strength()) >= 0) {
                continue;
            }
            AlertEntity alert = current != null ? current : new AlertEntity();
            alert.setCustomerId(customerId);
            alert.setCaseId(caseEntity.getId());
            alert.setRuleCode(hit.ruleCode());
            alert.setRuleName(hit.ruleName());
            alert.setStrength(hit.strength());
            alert.setPoints(hit.points());
            alert.setRuleParamsSnapshot(hit.ruleParamsSnapshot());
            alert.setEvidenceSnapshot(hit.evidenceSnapshot());
            alert.setWindowStart(hit.windowStart());
            alert.setWindowEnd(hit.windowEnd());
            alert.setCreatedAt(asOf);
            alert = alertRepository.save(alert);
            bestByRule.put(hit.ruleCode(), alert);
        }

        int score = bestByRule.values().stream().mapToInt(AlertEntity::getPoints).sum();
        caseEntity.setPriorityScore(score);
        caseEntity.setPriorityBand(band(score));
        caseEntity.setWindowStart(asOf.minus(24, ChronoUnit.HOURS));
        caseEntity.setWindowEnd(asOf);
        caseEntity = caseRepository.save(caseEntity);

        if (created) {
            auditService.recordAt(asOf, "CASE_OPENED", "CASE", caseEntity.getId(),
                    Map.of("caseRef", caseEntity.getCaseRef(), "priorityScore", score, "band", caseEntity.getPriorityBand()));
        }
        return Optional.of(caseEntity);
    }

    public static String band(int score) {
        if (score >= 70) return "RED";
        if (score >= 40) return "AMBER";
        return "GREEN";
    }

    private String nextCaseRef() {
        long seq = caseRepository.count() + 1;
        return String.format("CASE-2026-%04d", seq);
    }
}
