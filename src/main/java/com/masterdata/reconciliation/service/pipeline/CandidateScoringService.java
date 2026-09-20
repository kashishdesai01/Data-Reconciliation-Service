package com.masterdata.reconciliation.service.pipeline;

import com.masterdata.reconciliation.domain.model.ScoreResult;
import com.masterdata.reconciliation.service.MatchScoringService;
import com.masterdata.reconciliation.service.NormalizationService;
import com.masterdata.reconciliation.service.RulesetService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class CandidateScoringService {
    private final JdbcClient jdbc;
    private final MatchRunLifecycleService runs;
    private final NormalizationService normalization;
    private final MatchScoringService scoring;
    private final RulesetService rulesets;
    private final CandidateScoreWriter writer;
    private final Executor scoringExecutor;

    public CandidateScoringService(JdbcClient jdbc, MatchRunLifecycleService runs,
                                   NormalizationService normalization, MatchScoringService scoring,
                                   RulesetService rulesets, CandidateScoreWriter writer,
                                   @Qualifier("scoringExecutor") TaskExecutor scoringExecutor) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.normalization = normalization;
        this.scoring = scoring;
        this.rulesets = rulesets;
        this.writer = writer;
        this.scoringExecutor = scoringExecutor;
    }

    public void score(UUID runId) {
        runs.stage(runId, "SCORE_IN_PARALLEL");
        var rules = rulesets.get(runs.rulesetId(runId));
        int normalizationVersion = rules.normalizationVersion();
        List<Candidate> candidates = jdbc.sql("""
                SELECT id, left_revision_id, right_revision_id FROM match_candidates
                WHERE first_run_id = :runId AND score IS NULL ORDER BY left_revision_id, right_revision_id
                """).param("runId", runId).query((rs, rowNum) -> new Candidate(
                        rs.getObject("id", UUID.class), rs.getObject("left_revision_id", UUID.class),
                        rs.getObject("right_revision_id", UUID.class))).list();

        List<CompletableFuture<ScoredCandidate>> futures = candidates.stream()
                .map(candidate -> CompletableFuture.supplyAsync(() -> new ScoredCandidate(candidate,
                        scoring.score(normalization.find(candidate.leftRevisionId(), normalizationVersion),
                                normalization.find(candidate.rightRevisionId(), normalizationVersion), rules)),
                        scoringExecutor))
                .toList();
        for (CompletableFuture<ScoredCandidate> future : futures) {
            ScoredCandidate scored = future.join();
            writer.persist(scored.candidate().id(), scored.result());
        }
    }

    private record Candidate(UUID id, UUID leftRevisionId, UUID rightRevisionId) {
    }

    private record ScoredCandidate(Candidate candidate, ScoreResult result) {
    }
}
