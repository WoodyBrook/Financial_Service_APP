package com.bank.aml.service;

import com.bank.aml.config.AppProperties;
import com.bank.aml.domain.CustomerEntity;
import com.bank.aml.domain.SanctionsEntryEntity;
import com.bank.aml.domain.SanctionsHitEntity;
import com.bank.aml.domain.TransactionEntity;
import com.bank.aml.repo.CustomerRepository;
import com.bank.aml.repo.SanctionsEntryRepository;
import com.bank.aml.repo.SanctionsHitRepository;
import com.bank.aml.repo.TransactionRepository;
import com.bank.aml.util.JaroWinkler;
import com.bank.aml.util.Jsons;
import jakarta.persistence.EntityManager;
import com.bank.aml.web.dto.PaymentRequest;
import com.bank.aml.web.dto.PaymentResponse;
import com.bank.aml.web.dto.ResolveHitRequest;
import com.bank.aml.web.dto.ScreenRequest;
import com.bank.aml.web.dto.ScreenResponse;
import com.bank.aml.web.dto.SyncResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class SanctionsScreeningService {
    private static final double THRESHOLD = 0.85;
    private static final Set<String> RESOLVE = Set.of("FALSE_MATCH", "TARGET_MATCH");

    private final SanctionsEntryRepository entryRepository;
    private final SanctionsHitRepository hitRepository;
    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final FxRateService fxRateService;
    private final AuditService auditService;
    private final AppProperties appProperties;
    private final Clock clock;
    private final jakarta.persistence.EntityManager entityManager;

    @Transactional
    public int importList(String resourceLocation, String batchId) {
        try {
            Resource resource = new DefaultResourceLoader().getResource(resourceLocation);
            try (InputStream in = resource.getInputStream()) {
                JsonNode root = Jsons.MAPPER.readTree(in);
                JsonNode entries = root.path("entries");
                int upserts = 0;
                for (JsonNode n : entries) {
                    String sourceId = n.path("sourceUniqueId").asText();
                    SanctionsEntryEntity e = entryRepository.findBySourceUniqueId(sourceId).orElseGet(SanctionsEntryEntity::new);
                    e.setSourceUniqueId(sourceId);
                    e.setName(n.path("name").asText());
                    e.setEntityType(n.path("entityType").asText("INDIVIDUAL"));
                    e.setAliasesJson(n.path("aliases").isMissingNode() ? Jsons.emptyArr() : n.path("aliases"));
                    e.setIdentifiersJson(n.path("identifiers").isMissingNode() ? Jsons.emptyObj() : n.path("identifiers"));
                    e.setMeasuresJson(n.path("measures").isMissingNode() ? Jsons.emptyArr() : n.path("measures"));
                    String updated = n.path("sourceUpdatedAt").asText(null);
                    if (updated != null && !updated.isBlank()) {
                        e.setSourceUpdatedAt(Instant.parse(updated));
                    }
                    e.setImportBatchId(batchId);
                    e.setActive(true);
                    entryRepository.save(e);
                    upserts++;
                }
                auditService.record("SANCTIONS_LIST_IMPORTED", "SANCTIONS_LIST", 0L,
                        Map.of("batchId", batchId, "count", upserts, "resource", resourceLocation));
                return upserts;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to import sanctions list: " + ex.getMessage(), ex);
        }
    }

    public record MatchResult(SanctionsEntryEntity entry, double similarity, ObjectNode details) {}

    public Optional<MatchResult> bestMatch(String name, String dob, String nationality, String country, String entityType) {
        SanctionsEntryEntity best = null;
        double bestScore = 0;
        ObjectNode bestDetails = null;
        for (SanctionsEntryEntity e : entryRepository.findByActiveTrue()) {
            double score = JaroWinkler.similarity(name, e.getName());
            if (e.getAliasesJson() != null && e.getAliasesJson().isArray()) {
                for (JsonNode a : e.getAliasesJson()) {
                    score = Math.max(score, JaroWinkler.similarity(name, a.asText()));
                }
            }
            if (score < THRESHOLD) continue;
            ObjectNode details = buildDetails(name, dob, nationality, country, entityType, e, score);
            if (score > bestScore) {
                bestScore = score;
                best = e;
                bestDetails = details;
            }
        }
        if (best == null) return Optional.empty();
        return Optional.of(new MatchResult(best, bestScore, bestDetails));
    }

    private ObjectNode buildDetails(
            String name, String dob, String nationality, String country, String entityType,
            SanctionsEntryEntity e, double score) {
        JsonNode ids = e.getIdentifiersJson() == null ? Jsons.emptyObj() : e.getIdentifiersJson();
        ObjectNode root = Jsons.obj();
        ObjectNode nameN = Jsons.obj();
        nameN.put("input", name);
        nameN.put("listed", e.getName());
        nameN.put("similarity", BigDecimal.valueOf(score).setScale(3, RoundingMode.HALF_UP));
        nameN.put("algorithm", "jaro-winkler");
        root.set("name", nameN);

        root.set("dateOfBirth", attr(dob, text(ids, "dateOfBirth")));
        root.set("nationality", attr(nationality, text(ids, "nationality")));
        root.set("passport", attr(null, text(ids, "passport")));
        String listedType = e.getEntityType();
        ObjectNode et = Jsons.obj();
        et.put("input", entityType == null ? "UNKNOWN" : entityType);
        et.put("listed", listedType);
        et.put("match", entityType != null && entityType.equalsIgnoreCase(listedType) ? "EXACT" : "PARTIAL");
        root.set("entityType", et);
        root.put("overall", "POTENTIAL_MATCH");
        if (country != null) {
            root.set("country", attr(country, text(ids, "country")));
        }
        return root;
    }

    private static ObjectNode attr(String input, String listed) {
        ObjectNode n = Jsons.obj();
        n.put("input", input);
        n.put("listed", listed);
        if (input == null || input.isBlank()) n.put("match", "NOT_PROVIDED");
        else if (listed == null || listed.isBlank()) n.put("match", "NOT_ON_LIST");
        else if (input.equalsIgnoreCase(listed)) n.put("match", "EXACT");
        else n.put("match", "MISMATCH");
        return n;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    @Transactional
    public PaymentResponse screenPayment(PaymentRequest req) {
        return screenPayment(req, PaymentExecutionContext.live(clock));
    }

    /**
     * Full screening path with an explicit execution context. FX date, createdAt, executedAt
     * and the payment audit events all derive from the same context, so a simulated payment
     * never needs a post-hoc UPDATE of its timestamps.
     */
    @Transactional
    public PaymentResponse screenPayment(PaymentRequest req, PaymentExecutionContext ctx) {
        CustomerEntity customer = customerRepository.findByCustomerRef(req.customerRef())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));

        LocalDate tradeDate = ctx.fxRateDate();
        BigDecimal gbp = fxRateService.toGbp(req.currency(), tradeDate, req.amount());

        TransactionEntity txn = new TransactionEntity();
        txn.setTxnRef(nextPaymentRef());
        txn.setCustomerId(customer.getId());
        txn.setAccountRef("ACC-" + customer.getCustomerRef());
        txn.setDirection("OUTBOUND");
        txn.setAmount(req.amount());
        txn.setCurrency(req.currency().toUpperCase());
        txn.setAmountGbp(gbp);
        txn.setFxRateUsed(fxRateService.rateToGbp(req.currency(), tradeDate));
        txn.setFxRateDate(tradeDate);
        txn.setCounterpartyName(req.beneficiaryName());
        txn.setCounterpartyRef("BEN-" + Math.abs(req.beneficiaryName().hashCode()));
        txn.setCounterpartyCountry(req.beneficiaryCountry().toUpperCase());
        txn.setCreatedAt(ctx.occurredAt());
        // executedAt stays null until screening clears the payment.
        txn.setStatus("SCREENING");
        txn = transactionRepository.save(txn);

        auditService.recordAt(ctx.occurredAt(), "PAYMENT_SCREENED", "CUSTOMER", customer.getId(),
                Map.of("txnId", txn.getId(), "txnRef", txn.getTxnRef(), "beneficiary", req.beneficiaryName()));

        Optional<MatchResult> match = bestMatch(
                req.beneficiaryName(), req.beneficiaryDob(), req.beneficiaryNationality(),
                req.beneficiaryCountry(), "INDIVIDUAL");
        if (match.isEmpty()) {
            txn.setStatus("RELEASED");
            txn.setExecutedAt(ctx.occurredAt());
            transactionRepository.save(txn);
            auditService.recordAt(ctx.occurredAt(), "PAYMENT_RELEASED", "CUSTOMER", customer.getId(),
                    Map.of("txnId", txn.getId(), "txnRef", txn.getTxnRef(), "reason", "No potential sanctions match"));
            return new PaymentResponse(
                    "RELEASED", null, txn.getId(), "No potential sanctions match",
                    txn.getTxnRef(), txn.getExecutedAt(), "NO_POTENTIAL_MATCH", null, null, null);
        }

        MatchResult m = match.get();
        txn.setStatus("HELD");
        transactionRepository.save(txn);
        SanctionsHitEntity hit = persistHit(
                "PAYMENT_SCREENING", txn.getId(), customer.getId(), req.beneficiaryName(), m);
        auditService.recordAt(ctx.occurredAt(), "PAYMENT_HELD", "SANCTIONS_HIT", hit.getId(),
                Map.of("txnId", txn.getId(), "txnRef", txn.getTxnRef(), "similarity", m.similarity()));
        JsonNode details = m.details();
        return new PaymentResponse(
                "HELD", hit.getId(), txn.getId(), "Payment held pending review",
                txn.getTxnRef(), null, "POTENTIAL_MATCH",
                BigDecimal.valueOf(m.similarity()).setScale(3, RoundingMode.HALF_UP),
                details.path("dateOfBirth").path("match").asText(null),
                details.path("nationality").path("match").asText(null));
    }

    /**
     * Payment references come from a database sequence, not from count()+1: under concurrent
     * submissions a row count hands the same reference to both callers.
     */
    private String nextPaymentRef() {
        Long next = (Long) entityManager
                .createNativeQuery("SELECT nextval('payment_ref_seq')")
                .getSingleResult();
        return String.format("PAY-%06d", next);
    }

    private SanctionsHitEntity persistHit(
            String trigger, Long txnId, Long customerId, String screenedName, MatchResult m) {
        SanctionsHitEntity hit = new SanctionsHitEntity();
        hit.setTriggerType(trigger);
        hit.setPaymentTxnId(txnId);
        hit.setCustomerId(customerId);
        hit.setScreenedName(screenedName);
        hit.setSanctionsEntryId(m.entry().getId());
        hit.setNameSimilarity(BigDecimal.valueOf(m.similarity()).setScale(3, RoundingMode.HALF_UP));
        hit.setStatus("POTENTIAL_MATCH");
        hit.setMatchDetailsSnapshot(m.details());
        ObjectNode snap = Jsons.obj();
        snap.put("id", m.entry().getId());
        snap.put("sourceUniqueId", m.entry().getSourceUniqueId());
        snap.put("name", m.entry().getName());
        snap.put("entityType", m.entry().getEntityType());
        snap.set("aliases", m.entry().getAliasesJson());
        snap.set("identifiers", m.entry().getIdentifiersJson());
        snap.set("measures", m.entry().getMeasuresJson());
        hit.setSanctionsEntrySnapshot(snap);
        return hitRepository.save(hit);
    }

    public List<SanctionsHitEntity> listHits(String status) {
        if (status == null || status.isBlank()) return hitRepository.findAll();
        return hitRepository.findByStatus(status);
    }

    @Transactional
    public SanctionsHitEntity resolve(Long id, ResolveHitRequest req) {
        if (req.rationale() == null || req.rationale().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rationale is required");
        }
        if (!RESOLVE.contains(req.outcome())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "outcome must be FALSE_MATCH or TARGET_MATCH");
        }
        if (hitRepository.findById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Hit not found");
        }
        // Single atomic claim: whoever flips POTENTIAL_MATCH first owns the outcome.
        int claimed = hitRepository.resolveIfUnresolved(
                id, req.outcome(), req.rationale(), appProperties.getActor(), Instant.now());
        SanctionsHitEntity hit = hitRepository.findById(id).orElseThrow();
        if (claimed == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Hit already resolved as " + hit.getStatus());
        }

        // Carry the decision through to the payment itself, otherwise "held" is only a label.
        String paymentStatus = null;
        if (hit.getPaymentTxnId() != null) {
            TransactionEntity txn = transactionRepository.findById(hit.getPaymentTxnId()).orElse(null);
            if (txn != null && "HELD".equals(txn.getStatus())) {
                if ("FALSE_MATCH".equals(req.outcome())) {
                    txn.setStatus("RELEASED_FALSE_MATCH");
                    txn.setExecutedAt(Instant.now());   // only now does the payment actually happen
                    auditService.record("PAYMENT_RELEASED", "CUSTOMER", txn.getCustomerId(),
                            Map.of("txnId", txn.getId(), "txnRef", txn.getTxnRef(),
                                    "hitId", hit.getId(), "reason", req.rationale()));
                } else {
                    txn.setStatus("RESTRICTED_TARGET_MATCH");   // never executed
                    auditService.record("PAYMENT_RESTRICTED", "CUSTOMER", txn.getCustomerId(),
                            Map.of("txnId", txn.getId(), "txnRef", txn.getTxnRef(),
                                    "hitId", hit.getId(), "reason", req.rationale()));
                }
                transactionRepository.save(txn);
                paymentStatus = txn.getStatus();
            }
        }

        Map<String, Object> details = new java.util.HashMap<>();
        details.put("outcome", req.outcome());
        details.put("rationale", req.rationale());
        details.put("paymentStatus", paymentStatus);
        auditService.record("SANCTIONS_HIT_RESOLVED", "SANCTIONS_HIT", hit.getId(), details);
        return hit;
    }

    @Transactional
    public SyncResponse syncListV2() {
        List<SanctionsEntryEntity> before = entryRepository.findAll();
        Map<String, Long> beforeIds = new java.util.HashMap<>();
        before.forEach(e -> beforeIds.put(e.getSourceUniqueId(), e.getId()));

        int total = importList(appProperties.getSanctions().getListV2(), "v2");
        int added = 0;
        int updated = 0;
        for (SanctionsEntryEntity e : entryRepository.findByImportBatchId("v2")) {
            if (!beforeIds.containsKey(e.getSourceUniqueId())) added++;
            else updated++;
        }
        // recount properly
        added = 0;
        updated = 0;
        for (SanctionsEntryEntity e : entryRepository.findAll()) {
            if ("v2".equals(e.getImportBatchId())) {
                if (!beforeIds.containsKey(e.getSourceUniqueId())) added++;
                else updated++;
            }
        }

        int rescreened = 0;
        int matches = 0;
        for (CustomerEntity c : customerRepository.findAll()) {
            rescreened++;
            Optional<MatchResult> m = bestMatch(c.getName(), null, null, c.getIncorporationCountry(), "ENTITY");
            if (m.isPresent()) {
                // only create if no open potential hit for same entry+customer
                boolean exists = hitRepository.findByCustomerId(c.getId()).stream()
                        .anyMatch(h -> h.getSanctionsEntryId().equals(m.get().entry().getId())
                                && "POTENTIAL_MATCH".equals(h.getStatus()));
                if (!exists) {
                    persistHit("LIST_UPDATE_RESCREEN", null, c.getId(), c.getName(), m.get());
                    matches++;
                }
            }
        }
        auditService.record("CUSTOMERS_RESCREENED", "SANCTIONS_LIST", 0L,
                Map.of("customersRescreened", rescreened, "potentialMatchesCreated", matches, "imported", total));
        return new SyncResponse(added, updated, rescreened, matches);
    }

    public ScreenResponse screenOpen(ScreenRequest req) {
        Optional<MatchResult> m = bestMatch(req.name(), req.dateOfBirth(), null, req.country(), null);
        if (m.isEmpty()) {
            return new ScreenResponse("NO_MATCH", BigDecimal.ZERO, null, null, null);
        }
        MatchResult r = m.get();
        return new ScreenResponse(
                "POTENTIAL_MATCH",
                BigDecimal.valueOf(r.similarity()).setScale(3, RoundingMode.HALF_UP),
                r.entry().getId(),
                r.entry().getMeasuresJson(),
                r.details());
    }

    @Transactional
    public void ensureV1Loaded() {
        if (entryRepository.count() == 0) {
            importList(appProperties.getSanctions().getListV1(), "v1");
            log.info("Loaded sanctions list-v1");
        }
    }
}
