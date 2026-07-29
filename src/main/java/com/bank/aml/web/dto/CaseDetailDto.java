package com.bank.aml.web.dto;

import com.bank.aml.domain.AiDraftEntity;
import com.bank.aml.domain.AlertEntity;
import com.bank.aml.domain.AuditEventEntity;
import com.bank.aml.domain.CaseEntity;
import com.bank.aml.domain.TransactionEntity;
import java.util.List;
import java.util.Map;

/**
 * @param focusTxnRef the single payment reference that best anchors the investigation —
 *                    the largest high-risk transfer cited by the alerts, when one exists
 */
public record CaseDetailDto(
        CaseEntity caseRecord,
        Map<String, Object> customer,
        List<AlertEntity> alerts,
        List<TransactionEntity> timeline,
        AiDraftEntity aiDraft,
        List<AuditEventEntity> auditEvents,
        String focusTxnRef) {}
