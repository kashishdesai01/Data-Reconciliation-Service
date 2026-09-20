package com.masterdata.reconciliation.service.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterdata.reconciliation.domain.model.ScoreResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CandidateScoreWriter {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public CandidateScoreWriter(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(UUID candidateId, ScoreResult result) {
        String applyStatus = "AUTO_MATCH".equals(result.decision()) ? "PENDING" : "NOT_APPLICABLE";
        jdbc.sql("""
                UPDATE match_candidates SET field_evidence = CAST(:evidence AS jsonb), score = :score,
                    automated_decision = :decision, decision_reason = :reason,
                    apply_status = :applyStatus, scored_at = now()
                WHERE id = :id AND score IS NULL
                """).param("evidence", write(result.evidence())).param("score", result.score())
                .param("decision", result.decision()).param("reason", result.reason())
                .param("applyStatus", applyStatus).param("id", candidateId).update();
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize score evidence", exception);
        }
    }
}
