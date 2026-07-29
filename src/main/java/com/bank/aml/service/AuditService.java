package com.bank.aml.service;

import com.bank.aml.config.AppProperties;
import com.bank.aml.domain.AuditEventEntity;
import com.bank.aml.repo.AuditEventRepository;
import com.bank.aml.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditEventRepository auditEventRepository;
    private final AppProperties appProperties;
    private final Clock clock;

    @Transactional
    public AuditEventEntity record(String action, String entityType, Long entityId, Map<String, ?> details) {
        return persist(clock.instant(), action, entityType, entityId,
                details == null ? Jsons.emptyObj() : Jsons.toTree(details));
    }

    @Transactional
    public AuditEventEntity record(String action, String entityType, Long entityId, JsonNode details) {
        return persist(clock.instant(), action, entityType, entityId,
                details == null ? Jsons.emptyObj() : details);
    }

    /**
     * System-simulated events (a payment executed "yesterday", a monitoring run "at 02:00")
     * carry the business time of the event itself, so the audit trail matches what the
     * business records show.
     */
    @Transactional
    public AuditEventEntity recordAt(Instant occurredAt, String action, String entityType, Long entityId, Map<String, ?> details) {
        return persist(occurredAt, action, entityType, entityId,
                details == null ? Jsons.emptyObj() : Jsons.toTree(details));
    }

    private AuditEventEntity persist(
            Instant occurredAt, String action, String entityType, Long entityId, JsonNode details) {
        AuditEventEntity e = new AuditEventEntity();
        e.setActor(appProperties.getActor());
        e.setOccurredAt(occurredAt);
        e.setAction(action);
        e.setEntityType(entityType);
        e.setEntityId(entityId);
        e.setDetailsJson(details);
        return auditEventRepository.save(e);
    }

    public List<AuditEventEntity> forEntity(String entityType, Long entityId) {
        return auditEventRepository.findByEntityTypeAndEntityIdOrderByOccurredAtDesc(entityType, entityId);
    }
}
