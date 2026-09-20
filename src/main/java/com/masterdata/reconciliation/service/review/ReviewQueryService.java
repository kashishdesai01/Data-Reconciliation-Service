package com.masterdata.reconciliation.service.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterdata.reconciliation.api.ApiException;
import com.masterdata.reconciliation.api.model.CursorPage;
import com.masterdata.reconciliation.service.GoldenRecordService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReviewQueryService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final GoldenRecordService goldens;
    private final ReviewCursorCodec cursors;

    public ReviewQueryService(JdbcClient jdbc, ObjectMapper objectMapper,
                              GoldenRecordService goldens, ReviewCursorCodec cursors) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.goldens = goldens;
        this.cursors = cursors;
    }

    public CursorPage<Map<String, Object>> list(String cursor, String type, UUID runId, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        ReviewCursorCodec.Cursor after = cursors.decode(cursor);
        List<Map<String, Object>> items = jdbc.sql("""
                SELECT ri.id, ri.run_id, ri.candidate_id, ri.type, ri.status, ri.version,
                       ri.context::text, ri.created_at, mc.score, mc.automated_decision,
                       mc.left_revision_id, mc.right_revision_id
                FROM review_items ri LEFT JOIN match_candidates mc ON mc.id = ri.candidate_id
                WHERE ri.status = 'PENDING'
                  AND (CAST(:type AS varchar) IS NULL OR ri.type = CAST(:type AS varchar))
                  AND (CAST(:runId AS uuid) IS NULL OR ri.run_id = CAST(:runId AS uuid))
                  AND (ri.created_at, ri.id) > (:afterTime, :afterId)
                ORDER BY ri.created_at, ri.id LIMIT :limit
                """).param("type", type).param("runId", runId)
                .param("afterTime", Timestamp.from(after == null ? Instant.EPOCH : after.time()))
                .param("afterId", after == null ? new UUID(0, 0) : after.id())
                .param("limit", limit + 1).query((rs, rowNum) -> summary(rs)).list();

        String nextCursor = null;
        if (items.size() > limit) {
            items = items.subList(0, limit);
            Map<String, Object> last = items.get(items.size() - 1);
            nextCursor = cursors.encode((Instant) last.get("createdAt"), (UUID) last.get("id"));
        }
        return new CursorPage<>(List.copyOf(items), nextCursor);
    }

    public Map<String, Object> detail(UUID reviewId) {
        Map<String, Object> detail = jdbc.sql("""
                SELECT ri.id, ri.run_id, ri.candidate_id, ri.type, ri.status, ri.version,
                       ri.context::text, ri.created_at, ri.resolved_at,
                       mc.ruleset_id, mc.left_revision_id, mc.right_revision_id,
                       mc.blocking_rules::text, mc.field_evidence::text, mc.score,
                       mc.automated_decision, mc.decision_reason,
                       lr.raw_payload::text left_payload, rr.raw_payload::text right_payload,
                       ls.id left_source_id, rs.id right_source_id
                FROM review_items ri
                LEFT JOIN match_candidates mc ON mc.id = ri.candidate_id
                LEFT JOIN source_record_revisions lr ON lr.id = mc.left_revision_id
                LEFT JOIN source_record_revisions rr ON rr.id = mc.right_revision_id
                LEFT JOIN source_records ls ON ls.id = lr.source_record_id
                LEFT JOIN source_records rs ON rs.id = rr.source_record_id
                WHERE ri.id = :id
                """).param("id", reviewId).query((rs, rowNum) -> detail(rs)).optional()
                .orElseThrow(() ->
                        new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "Review item not found"));
        detail.put("decisions", decisions(reviewId));
        return detail;
    }

    private Map<String, Object> summary(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getObject("id", UUID.class));
        row.put("runId", rs.getObject("run_id", UUID.class));
        row.put("candidateId", rs.getObject("candidate_id", UUID.class));
        row.put("type", rs.getString("type"));
        row.put("status", rs.getString("status"));
        row.put("version", rs.getLong("version"));
        row.put("context", read(rs.getString("context")));
        row.put("createdAt", instant(rs, "created_at"));
        row.put("score", rs.getObject("score"));
        row.put("automatedDecision", rs.getString("automated_decision"));
        row.put("leftRevisionId", rs.getObject("left_revision_id", UUID.class));
        row.put("rightRevisionId", rs.getObject("right_revision_id", UUID.class));
        return row;
    }

    private Map<String, Object> detail(ResultSet rs) throws SQLException {
        Map<String, Object> row = summary(rs);
        row.put("resolvedAt", instant(rs, "resolved_at"));
        row.put("rulesetId", rs.getObject("ruleset_id", UUID.class));
        row.put("blockingRules", read(rs.getString("blocking_rules")));
        row.put("evidence", read(rs.getString("field_evidence")));
        row.put("decisionReason", rs.getString("decision_reason"));
        row.put("leftPayload", read(rs.getString("left_payload")));
        row.put("rightPayload", read(rs.getString("right_payload")));
        UUID leftSource = rs.getObject("left_source_id", UUID.class);
        UUID rightSource = rs.getObject("right_source_id", UUID.class);
        row.put("leftSourceId", leftSource);
        row.put("rightSourceId", rightSource);
        row.put("leftGoldenId", leftSource == null ? null : goldens.membership(leftSource));
        row.put("rightGoldenId", rightSource == null ? null : goldens.membership(rightSource));
        return row;
    }

    private List<Map<String, Object>> decisions(UUID reviewId) {
        return jdbc.sql("""
                SELECT id, decision, actor, reason, idempotency_key, supersedes_id, created_at
                FROM review_decisions WHERE review_item_id = :id ORDER BY created_at, id
                """).param("id", reviewId).query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("decision", rs.getString("decision"));
                    row.put("actor", rs.getString("actor"));
                    row.put("reason", rs.getString("reason"));
                    row.put("idempotencyKey", rs.getString("idempotency_key"));
                    row.put("supersedesDecisionId", rs.getObject("supersedes_id", UUID.class));
                    row.put("createdAt", instant(rs, "created_at"));
                    return row;
                }).list();
    }

    private Object read(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to deserialize review data", exception);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
