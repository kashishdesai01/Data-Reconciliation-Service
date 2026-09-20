package com.masterdata.reconciliation.service.pipeline;

import com.masterdata.reconciliation.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class MatchRunLifecycleService {
    private final JdbcClient jdbc;

    public MatchRunLifecycleService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID create() {
        UUID ruleset = jdbc.sql("SELECT id FROM rule_sets WHERE active").query(UUID.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NO_ACTIVE_RULESET", "No active ruleset exists"));
        UUID runId = UUID.randomUUID();
        jdbc.sql("INSERT INTO match_runs (id, ruleset_id, status, stage) VALUES (:id, :ruleset, 'STARTING', 'QUEUED')")
                .param("id", runId).param("ruleset", ruleset).update();
        return runId;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stage(UUID runId, String stage) {
        jdbc.sql("""
                UPDATE match_runs SET status = 'RUNNING', stage = :stage,
                    started_at = COALESCE(started_at, now()) WHERE id = :id
                """).param("stage", stage).param("id", runId).update();
    }

    @Transactional
    public void finish(UUID runId) {
        stage(runId, "FINALIZE");
        jdbc.sql("UPDATE match_run_items SET state = 'COMPLETED', updated_at = now() WHERE run_id = :runId AND state = 'PROCESSING'")
                .param("runId", runId).update();
        Counts counts = jdbc.sql("""
                SELECT count(*) total,
                       count(*) FILTER (WHERE automated_decision = 'AUTO_MATCH') auto_count,
                       count(*) FILTER (WHERE automated_decision = 'REVIEW') review_count,
                       count(*) FILTER (WHERE automated_decision = 'NO_MATCH') no_count,
                       count(*) FILTER (WHERE automated_decision = 'INSUFFICIENT_EVIDENCE') insufficient_count
                FROM match_candidates WHERE first_run_id = :runId
                """).param("runId", runId).query((rs, rowNum) -> new Counts(
                        rs.getInt("total"), rs.getInt("auto_count"), rs.getInt("review_count"),
                        rs.getInt("no_count"), rs.getInt("insufficient_count"))).single();
        int failures = jdbc.sql("SELECT apply_failures FROM match_runs WHERE id = :id")
                .param("id", runId).query(Integer.class).single();
        jdbc.sql("""
                UPDATE match_runs SET status = :status, stage = 'DONE', candidate_count = :total,
                    auto_match_count = :autoCount, review_count = :reviewCount, no_match_count = :noCount,
                    insufficient_count = :insufficientCount, failure_summary = NULL, completed_at = now()
                WHERE id = :runId
                """).param("status", failures > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED")
                .param("total", counts.total()).param("autoCount", counts.autoCount())
                .param("reviewCount", counts.reviewCount()).param("noCount", counts.noCount())
                .param("insufficientCount", counts.insufficientCount()).param("runId", runId).update();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID runId, Throwable failure) {
        jdbc.sql("""
                UPDATE match_runs SET status = 'FAILED', failure_summary = :failure, completed_at = now()
                WHERE id = :runId AND status NOT IN ('COMPLETED','COMPLETED_WITH_ERRORS')
                """).param("failure", safeMessage(failure)).param("runId", runId).update();
    }

    public Map<String, Object> status(UUID runId) {
        return jdbc.sql("""
                SELECT id, ruleset_id, status, stage, claimed_count, candidate_count, auto_match_count,
                       review_count, no_match_count, insufficient_count, oversized_blocks,
                       max_observed_block_size, apply_failures, retry_count, failure_summary,
                       started_at, completed_at, created_at
                FROM match_runs WHERE id = :id
                """).param("id", runId).query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("rulesetId", rs.getObject("ruleset_id", UUID.class));
                    row.put("status", rs.getString("status"));
                    row.put("stage", rs.getString("stage"));
                    row.put("claimedCount", rs.getInt("claimed_count"));
                    row.put("candidateCount", rs.getInt("candidate_count"));
                    row.put("autoMatchCount", rs.getInt("auto_match_count"));
                    row.put("reviewCount", rs.getInt("review_count"));
                    row.put("noMatchCount", rs.getInt("no_match_count"));
                    row.put("insufficientCount", rs.getInt("insufficient_count"));
                    row.put("oversizedBlocks", rs.getInt("oversized_blocks"));
                    row.put("maxObservedBlockSize", rs.getInt("max_observed_block_size"));
                    row.put("applyFailures", rs.getInt("apply_failures"));
                    row.put("retryCount", rs.getInt("retry_count"));
                    row.put("failureSummary", rs.getString("failure_summary"));
                    row.put("startedAt", instant(rs, "started_at"));
                    row.put("completedAt", instant(rs, "completed_at"));
                    row.put("createdAt", instant(rs, "created_at"));
                    return row;
                }).optional().orElseThrow(() ->
                        new ApiException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Match run not found"));
    }

    public UUID rulesetId(UUID runId) {
        return jdbc.sql("SELECT ruleset_id FROM match_runs WHERE id = :id")
                .param("id", runId).query(UUID.class).single();
    }

    private static String safeMessage(Throwable failure) {
        String value = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record Counts(int total, int autoCount, int reviewCount, int noCount, int insufficientCount) {
    }
}
