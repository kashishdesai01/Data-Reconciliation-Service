package com.masterdata.reconciliation.service.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterdata.reconciliation.api.ApiException;
import com.masterdata.reconciliation.api.model.DecisionRequest;
import com.masterdata.reconciliation.service.ReconciliationPipelineService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ReviewDecisionService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final ReconciliationPipelineService pipeline;

    public ReviewDecisionService(JdbcClient jdbc, ObjectMapper objectMapper,
                                 ReconciliationPipelineService pipeline) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.pipeline = pipeline;
    }

    @Transactional
    public Map<String, Object> decide(UUID reviewId, DecisionRequest request, String actor) {
        String decision = normalizeDecision(request.decision());
        lockIdempotencyKey(request.idempotencyKey());
        ExistingDecision existing = findExisting(request.idempotencyKey());
        if (existing != null) {
            validateReplay(existing, reviewId, decision, request.supersedesDecisionId());
            return existing.result();
        }

        LockedReview item = lockReview(reviewId);
        validateVersion(item, request.expectedVersion());
        LatestDecision latest = latestDecision(reviewId);
        validateState(item, latest, decision, request.supersedesDecisionId());

        Map<String, Object> result = applyDecision(reviewId, item, decision, request, actor);
        appendDecision(reviewId, request, decision, actor, result);
        markResolved(reviewId, (long) result.get("version"));
        return Map.copyOf(result);
    }

    private String normalizeDecision(String rawDecision) {
        String decision = rawDecision.trim().toUpperCase();
        if (!decision.equals("MATCH") && !decision.equals("NO_MATCH")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DECISION",
                    "Decision must be MATCH or NO_MATCH");
        }
        return decision;
    }

    private void lockIdempotencyKey(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))::text")
                .param("key", key).query(String.class).single();
    }

    private ExistingDecision findExisting(String key) {
        return jdbc.sql("""
                SELECT review_item_id, decision, supersedes_id, result::text
                FROM review_decisions WHERE idempotency_key = :key
                """).param("key", key).query((rs, rowNum) -> new ExistingDecision(
                        rs.getObject("review_item_id", UUID.class), rs.getString("decision"),
                        rs.getObject("supersedes_id", UUID.class), readMap(rs.getString("result"))))
                .optional().orElse(null);
    }

    private void validateReplay(ExistingDecision existing, UUID reviewId, String decision,
                                UUID supersedesDecisionId) {
        if (!existing.reviewId().equals(reviewId) || !existing.decision().equals(decision)
                || !Objects.equals(existing.supersedesId(), supersedesDecisionId)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency key was used for a different decision");
        }
    }

    private LockedReview lockReview(UUID reviewId) {
        return jdbc.sql("""
                SELECT ri.status, ri.version, ri.candidate_id, mc.left_revision_id, mc.right_revision_id
                FROM review_items ri LEFT JOIN match_candidates mc ON mc.id = ri.candidate_id
                WHERE ri.id = :id FOR UPDATE OF ri
                """).param("id", reviewId).query((rs, rowNum) -> new LockedReview(
                        rs.getString("status"), rs.getLong("version"),
                        rs.getObject("candidate_id", UUID.class),
                        rs.getObject("left_revision_id", UUID.class),
                        rs.getObject("right_revision_id", UUID.class)))
                .optional().orElseThrow(() ->
                        new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "Review item not found"));
    }

    private void validateVersion(LockedReview item, long expectedVersion) {
        if (item.version() != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_REVIEW_VERSION",
                    "Review item changed; refresh before deciding");
        }
    }

    private LatestDecision latestDecision(UUID reviewId) {
        return jdbc.sql("""
                SELECT id, decision FROM review_decisions WHERE review_item_id = :reviewId
                ORDER BY created_at DESC, id DESC LIMIT 1
                """).param("reviewId", reviewId).query((rs, rowNum) ->
                        new LatestDecision(rs.getObject("id", UUID.class), rs.getString("decision")))
                .optional().orElse(null);
    }

    private void validateState(LockedReview item, LatestDecision latest, String decision,
                               UUID supersedesDecisionId) {
        boolean correction = supersedesDecisionId != null;
        if (!correction && !item.status().equals("PENDING")) {
            throw new ApiException(HttpStatus.CONFLICT, "REVIEW_ALREADY_RESOLVED",
                    "Review item is resolved; a correction must identify the decision it supersedes");
        }
        if (correction && (latest == null || !latest.id().equals(supersedesDecisionId))) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_SUPERSESSION",
                    "supersedesDecisionId must identify the latest decision");
        }
        if (correction && latest.decision().equals(decision)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REDUNDANT_CORRECTION",
                    "A correction must change the latest decision");
        }
    }

    private Map<String, Object> applyDecision(UUID reviewId, LockedReview item, String decision,
                                              DecisionRequest request, String actor) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reviewId", reviewId);
        result.put("decision", decision);
        if (decision.equals("MATCH")) {
            UUID goldenId = pipeline.applyReviewerMatch(
                    item.candidateId(), item.leftRevisionId(), item.rightRevisionId(), actor,
                    request.reason() == null ? "Human review match" : request.reason());
            result.put("goldenRecordId", goldenId);
        }
        result.put("version", item.version() + 1);
        return result;
    }

    private void appendDecision(UUID reviewId, DecisionRequest request, String decision,
                                String actor, Map<String, Object> result) {
        jdbc.sql("""
                INSERT INTO review_decisions
                    (id, review_item_id, decision, actor, reason, idempotency_key, supersedes_id, result)
                VALUES (:id, :reviewId, :decision, :actor, :reason, :key,
                    :supersedes, CAST(:result AS jsonb))
                """).param("id", UUID.randomUUID()).param("reviewId", reviewId)
                .param("decision", decision).param("actor", actor).param("reason", request.reason())
                .param("key", request.idempotencyKey()).param("supersedes", request.supersedesDecisionId())
                .param("result", write(result)).update();
    }

    private void markResolved(UUID reviewId, long newVersion) {
        jdbc.sql("""
                UPDATE review_items SET status = 'RESOLVED', version = :version, resolved_at = now()
                WHERE id = :id
                """).param("version", newVersion).param("id", reviewId).update();
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to deserialize decision result", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize decision result", exception);
        }
    }

    private record ExistingDecision(UUID reviewId, String decision, UUID supersedesId,
                                    Map<String, Object> result) {
    }

    private record LatestDecision(UUID id, String decision) {
    }

    private record LockedReview(String status, long version, UUID candidateId,
                                UUID leftRevisionId, UUID rightRevisionId) {
    }
}
