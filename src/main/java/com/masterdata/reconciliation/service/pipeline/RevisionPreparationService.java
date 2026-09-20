package com.masterdata.reconciliation.service.pipeline;

import com.masterdata.reconciliation.service.NormalizationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class RevisionPreparationService {
    private final JdbcClient jdbc;
    private final MatchRunLifecycleService runs;
    private final NormalizationService normalization;

    public RevisionPreparationService(JdbcClient jdbc, MatchRunLifecycleService runs,
                                      NormalizationService normalization) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.normalization = normalization;
    }

    @Transactional
    public void claim(UUID runId) {
        runs.stage(runId, "CLAIM_REVISIONS");
        UUID ruleset = runs.rulesetId(runId);
        jdbc.sql("""
                INSERT INTO match_run_items (id, run_id, source_revision_id, ruleset_id, state)
                SELECT gen_random_uuid(), :runId, sr.current_revision_id, :ruleset, 'CLAIMED'
                FROM source_records sr
                WHERE sr.current_revision_id IS NOT NULL
                  AND NOT EXISTS (
                    SELECT 1 FROM match_run_items mri
                    WHERE mri.source_revision_id = sr.current_revision_id AND mri.ruleset_id = :ruleset)
                ORDER BY sr.current_revision_id
                ON CONFLICT DO NOTHING
                """).param("runId", runId).param("ruleset", ruleset).update();
        int count = jdbc.sql("SELECT count(*) FROM match_run_items WHERE run_id = :runId")
                .param("runId", runId).query(Integer.class).single();
        jdbc.sql("UPDATE match_runs SET claimed_count = :count WHERE id = :runId")
                .param("count", count).param("runId", runId).update();
    }

    @Transactional
    public void normalize(UUID runId) {
        runs.stage(runId, "NORMALIZE");
        jdbc.sql("UPDATE match_run_items SET state = 'PROCESSING', updated_at = now() WHERE run_id = :runId AND state = 'CLAIMED'")
                .param("runId", runId).update();
        normalization.normalizeClaimed(runId);
    }
}
