package com.masterdata.reconciliation.service;

import com.masterdata.reconciliation.service.pipeline.CandidateGenerationService;
import com.masterdata.reconciliation.service.pipeline.CandidateScoringService;
import com.masterdata.reconciliation.service.pipeline.ClusterResolutionService;
import com.masterdata.reconciliation.service.pipeline.MatchRunLifecycleService;
import com.masterdata.reconciliation.service.pipeline.ReviewItemService;
import com.masterdata.reconciliation.service.pipeline.RevisionPreparationService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Stable application facade used by the batch job and API layer.
 *
 * <p>Each pipeline stage delegates to a single-purpose service so transaction,
 * persistence, and domain responsibilities remain independently reviewable.</p>
 */
@Service
public class ReconciliationPipelineService {
    private final MatchRunLifecycleService runs;
    private final RevisionPreparationService preparation;
    private final CandidateGenerationService candidateGeneration;
    private final CandidateScoringService candidateScoring;
    private final ClusterResolutionService clusterResolution;
    private final ReviewItemService reviewItems;

    public ReconciliationPipelineService(MatchRunLifecycleService runs,
                                         RevisionPreparationService preparation,
                                         CandidateGenerationService candidateGeneration,
                                         CandidateScoringService candidateScoring,
                                         ClusterResolutionService clusterResolution,
                                         ReviewItemService reviewItems) {
        this.runs = runs;
        this.preparation = preparation;
        this.candidateGeneration = candidateGeneration;
        this.candidateScoring = candidateScoring;
        this.clusterResolution = clusterResolution;
        this.reviewItems = reviewItems;
    }

    public UUID createRun() {
        return runs.create();
    }

    public void claim(UUID runId) {
        preparation.claim(runId);
    }

    public void normalize(UUID runId) {
        preparation.normalize(runId);
    }

    public void generateCandidates(UUID runId) {
        candidateGeneration.generate(runId);
    }

    public void scoreCandidates(UUID runId) {
        candidateScoring.score(runId);
    }

    public void resolve(UUID runId) {
        clusterResolution.resolve(runId);
    }

    public void publishReviews(UUID runId) {
        reviewItems.publishCandidateReviews(runId);
    }

    public void finish(UUID runId) {
        runs.finish(runId);
    }

    public void fail(UUID runId, Throwable failure) {
        runs.fail(runId, failure);
    }

    public Map<String, Object> runStatus(UUID runId) {
        return runs.status(runId);
    }

    public UUID applyReviewerMatch(UUID candidateId, UUID authorizedLeftRevision,
                                   UUID authorizedRightRevision, String actor, String reason) {
        return clusterResolution.applyReviewerMatch(
                candidateId, authorizedLeftRevision, authorizedRightRevision, actor, reason);
    }
}
