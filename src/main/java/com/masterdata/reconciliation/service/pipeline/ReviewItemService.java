package com.masterdata.reconciliation.service.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReviewItemService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final MatchRunLifecycleService runs;

    public ReviewItemService(JdbcClient jdbc, ObjectMapper objectMapper, MatchRunLifecycleService runs) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.runs = runs;
    }

    @Transactional
    public void publishCandidateReviews(UUID runId) {
        runs.stage(runId, "PUBLISH_REVIEW_ITEMS");
        List<UUID> candidates = jdbc.sql("""
                SELECT id FROM match_candidates
                WHERE first_run_id = :runId AND automated_decision = 'REVIEW'
                ORDER BY score DESC, id
                """).param("runId", runId).query(UUID.class).list();
        for (UUID candidateId : candidates) {
            create(runId, candidateId, "CANDIDATE", "Automated score requires human review");
        }
    }

    public void createConflict(UUID runId, UUID candidateId, String type, String reason) {
        create(runId, candidateId, type, reason);
        jdbc.sql("UPDATE match_candidates SET apply_status = 'CONFLICT', apply_failure = :reason WHERE id = :id")
                .param("reason", reason).param("id", candidateId).update();
    }

    private void create(UUID runId, UUID candidateId, String type, String reason) {
        jdbc.sql("""
                INSERT INTO review_items (id, run_id, candidate_id, type, status, context)
                VALUES (:id, :runId, :candidateId, :type, 'PENDING', CAST(:context AS jsonb))
                ON CONFLICT DO NOTHING
                """).param("id", UUID.randomUUID()).param("runId", runId).param("candidateId", candidateId)
                .param("type", type).param("context", write(Map.of("reason", reason))).update();
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize review context", exception);
        }
    }
}
