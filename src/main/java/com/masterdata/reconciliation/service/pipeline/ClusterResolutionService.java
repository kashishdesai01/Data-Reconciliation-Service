package com.masterdata.reconciliation.service.pipeline;

import com.masterdata.reconciliation.api.ApiException;
import com.masterdata.reconciliation.domain.model.CandidatePair;
import com.masterdata.reconciliation.domain.model.ClusterValidation;
import com.masterdata.reconciliation.service.GoldenRecordService;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ClusterResolutionService {
    private static final int MAX_APPLY_ATTEMPTS = 3;

    private final JdbcClient jdbc;
    private final GoldenRecordService goldens;
    private final MatchRunLifecycleService runs;
    private final ClusterJoinValidator validator;
    private final ReviewItemService reviewItems;
    private final TransactionTemplate itemTransaction;

    public ClusterResolutionService(JdbcClient jdbc, GoldenRecordService goldens,
                                    MatchRunLifecycleService runs, ClusterJoinValidator validator,
                                    ReviewItemService reviewItems, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.goldens = goldens;
        this.runs = runs;
        this.validator = validator;
        this.reviewItems = reviewItems;
        var definition = new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        definition.setName("reconciliation-item");
        definition.setTimeout(30);
        this.itemTransaction = new TransactionTemplate(transactionManager, definition);
    }

    public void resolve(UUID runId) {
        runs.stage(runId, "RESOLVE_CLUSTERS");
        UUID rulesetId = runs.rulesetId(runId);
        for (UUID sourceId : claimedSourceRecords(runId)) {
            itemTransaction.executeWithoutResult(ignored -> goldens.ensureSingleton(sourceId, rulesetId));
        }
        for (ResolutionCandidate candidate : automaticCandidates(runId)) {
            applyWithRetry(runId, rulesetId, candidate);
        }
    }

    @Transactional
    public UUID applyReviewerMatch(UUID candidateId, UUID authorizedLeftRevision, UUID authorizedRightRevision,
                                   String actor, String reason) {
        ReviewerCandidate candidate = loadReviewerCandidate(candidateId);
        if (!CandidatePair.of(candidate.leftRevisionId(), candidate.rightRevisionId())
                .equals(CandidatePair.of(authorizedLeftRevision, authorizedRightRevision))) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_REVIEW", "Review evidence changed");
        }
        RevisionEndpoint left = endpoint(candidate.leftRevisionId());
        RevisionEndpoint right = endpoint(candidate.rightRevisionId());
        if (!left.current() || !right.current()) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_REVIEW", "One or both source revisions changed");
        }
        UUID leftGolden = goldens.ensureSingleton(left.sourceId(), candidate.rulesetId());
        UUID rightGolden = goldens.ensureSingleton(right.sourceId(), candidate.rulesetId());
        if (leftGolden.equals(rightGolden)) return leftGolden;

        ClusterValidation validation = validator.validate(leftGolden, rightGolden, candidate.rulesetId(),
                CandidatePair.of(left.sourceId(), right.sourceId()));
        if (!validation.allowed()) {
            throw new ApiException(HttpStatus.CONFLICT, "CLUSTER_CONFLICT", validation.reason());
        }
        UUID result = goldens.merge(left.sourceId(), right.sourceId(), candidate.rulesetId(), actor, reason);
        markApplied(candidateId);
        return result;
    }

    private void applyWithRetry(UUID runId, UUID rulesetId, ResolutionCandidate candidate) {
        for (int attempt = 1; attempt <= MAX_APPLY_ATTEMPTS; attempt++) {
            try {
                itemTransaction.executeWithoutResult(ignored -> applyAutomatic(runId, rulesetId, candidate));
                return;
            } catch (TransientDataAccessException retryable) {
                if (attempt == MAX_APPLY_ATTEMPTS) {
                    recordApplyFailure(runId, candidate.id(), retryable);
                    return;
                }
                recordRetry(runId);
                if (!backoff(runId, candidate.id())) return;
            } catch (RuntimeException failure) {
                recordApplyFailure(runId, candidate.id(), failure);
                return;
            }
        }
    }

    private void applyAutomatic(UUID runId, UUID rulesetId, ResolutionCandidate candidate) {
        RevisionEndpoint left = endpoint(candidate.leftRevisionId());
        RevisionEndpoint right = endpoint(candidate.rightRevisionId());
        if (!left.current() || !right.current()) {
            reviewItems.createConflict(runId, candidate.id(), "CLUSTER_CONFLICT",
                    "Candidate revision is no longer current");
            return;
        }
        UUID leftGolden = goldens.ensureSingleton(left.sourceId(), rulesetId);
        UUID rightGolden = goldens.ensureSingleton(right.sourceId(), rulesetId);
        if (leftGolden.equals(rightGolden)) {
            markApplied(candidate.id());
            return;
        }
        if ((left.revisionNumber() > 1 && left.preexistingMembership())
                || (right.revisionNumber() > 1 && right.preexistingMembership())) {
            reviewItems.createConflict(runId, candidate.id(), "RELINK_REVIEW",
                    "Changed linked record matches a different golden record");
            return;
        }
        ClusterValidation validation = validator.validate(leftGolden, rightGolden, rulesetId, null);
        if (!validation.allowed()) {
            reviewItems.createConflict(runId, candidate.id(), "CLUSTER_CONFLICT", validation.reason());
            return;
        }
        goldens.merge(left.sourceId(), right.sourceId(), rulesetId, "system",
                "Automatic complete-link match");
        markApplied(candidate.id());
    }

    private List<ResolutionCandidate> automaticCandidates(UUID runId) {
        return jdbc.sql("""
                SELECT id, left_revision_id, right_revision_id, score
                FROM match_candidates WHERE first_run_id = :runId AND automated_decision = 'AUTO_MATCH'
                ORDER BY score DESC, left_revision_id, right_revision_id
                """).param("runId", runId).query((rs, rowNum) -> new ResolutionCandidate(
                        rs.getObject("id", UUID.class), rs.getObject("left_revision_id", UUID.class),
                        rs.getObject("right_revision_id", UUID.class), rs.getDouble("score"))).list();
    }

    private ReviewerCandidate loadReviewerCandidate(UUID candidateId) {
        return jdbc.sql("""
                SELECT ruleset_id, left_revision_id, right_revision_id FROM match_candidates WHERE id = :id
                """).param("id", candidateId).query((rs, rowNum) -> new ReviewerCandidate(
                        rs.getObject("ruleset_id", UUID.class), rs.getObject("left_revision_id", UUID.class),
                        rs.getObject("right_revision_id", UUID.class))).optional()
                .orElseThrow(() ->
                        new ApiException(HttpStatus.CONFLICT, "STALE_REVIEW", "Candidate no longer exists"));
    }

    private RevisionEndpoint endpoint(UUID revisionId) {
        return jdbc.sql("""
                SELECT sr.id source_id, sr.current_revision_id = rev.id current,
                       rev.revision_number,
                       EXISTS(SELECT 1 FROM golden_memberships gm WHERE gm.source_record_id = sr.id) member
                FROM source_record_revisions rev JOIN source_records sr ON sr.id = rev.source_record_id
                WHERE rev.id = :revisionId FOR UPDATE OF sr
                """).param("revisionId", revisionId).query((rs, rowNum) -> new RevisionEndpoint(
                        rs.getObject("source_id", UUID.class), rs.getBoolean("current"),
                        rs.getInt("revision_number"), rs.getBoolean("member"))).single();
    }

    private List<UUID> claimedSourceRecords(UUID runId) {
        return jdbc.sql("""
                SELECT rev.source_record_id FROM match_run_items mri
                JOIN source_record_revisions rev ON rev.id = mri.source_revision_id
                WHERE mri.run_id = :id ORDER BY rev.source_record_id
                """).param("id", runId).query(UUID.class).list();
    }

    private void markApplied(UUID candidateId) {
        jdbc.sql("UPDATE match_candidates SET apply_status = 'APPLIED', apply_failure = NULL WHERE id = :id")
                .param("id", candidateId).update();
    }

    private void recordApplyFailure(UUID runId, UUID candidateId, Throwable failure) {
        itemTransaction.executeWithoutResult(ignored -> {
            jdbc.sql("UPDATE match_candidates SET apply_status = 'FAILED', apply_failure = :failure WHERE id = :id")
                    .param("failure", safeMessage(failure)).param("id", candidateId).update();
            jdbc.sql("UPDATE match_runs SET apply_failures = apply_failures + 1 WHERE id = :runId")
                    .param("runId", runId).update();
        });
    }

    private void recordRetry(UUID runId) {
        itemTransaction.executeWithoutResult(ignored -> jdbc.sql(
                "UPDATE match_runs SET retry_count = retry_count + 1 WHERE id = :runId")
                .param("runId", runId).update());
    }

    private boolean backoff(UUID runId, UUID candidateId) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(25, 101));
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            recordApplyFailure(runId, candidateId, interrupted);
            return false;
        }
    }

    private static String safeMessage(Throwable failure) {
        String value = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }

    private record ResolutionCandidate(UUID id, UUID leftRevisionId, UUID rightRevisionId, double score) {
    }

    private record ReviewerCandidate(UUID rulesetId, UUID leftRevisionId, UUID rightRevisionId) {
    }

    private record RevisionEndpoint(UUID sourceId, boolean current, int revisionNumber,
                                    boolean preexistingMembership) {
    }
}
