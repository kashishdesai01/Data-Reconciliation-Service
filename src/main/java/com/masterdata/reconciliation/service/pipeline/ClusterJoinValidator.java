package com.masterdata.reconciliation.service.pipeline;

import com.masterdata.reconciliation.domain.model.CandidatePair;
import com.masterdata.reconciliation.domain.model.ClusterValidation;
import com.masterdata.reconciliation.domain.model.ScoreResult;
import com.masterdata.reconciliation.service.GoldenRecordService;
import com.masterdata.reconciliation.service.MatchScoringService;
import com.masterdata.reconciliation.service.NormalizationService;
import com.masterdata.reconciliation.service.RulesetService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ClusterJoinValidator {
    private final JdbcClient jdbc;
    private final GoldenRecordService goldens;
    private final RulesetService rulesets;
    private final NormalizationService normalization;
    private final MatchScoringService scoring;

    public ClusterJoinValidator(JdbcClient jdbc, GoldenRecordService goldens, RulesetService rulesets,
                                NormalizationService normalization, MatchScoringService scoring) {
        this.jdbc = jdbc;
        this.goldens = goldens;
        this.rulesets = rulesets;
        this.normalization = normalization;
        this.scoring = scoring;
    }

    public ClusterValidation validate(UUID leftGolden, UUID rightGolden, UUID rulesetId,
                                      CandidatePair reviewerAuthorizedPair) {
        List<UUID> leftMembers = goldens.members(leftGolden);
        List<UUID> rightMembers = goldens.members(rightGolden);
        var rules = rulesets.get(rulesetId);
        if (leftMembers.size() + rightMembers.size() > rules.maxClusterSize()) {
            return ClusterValidation.rejected(
                    "Resulting cluster exceeds " + rules.maxClusterSize() + " source records");
        }
        int normalizationVersion = rules.normalizationVersion();
        for (UUID leftSource : leftMembers) {
            for (UUID rightSource : rightMembers) {
                CandidatePair sourcePair = CandidatePair.of(leftSource, rightSource);
                if (reviewerAuthorizedPair != null && reviewerAuthorizedPair.equals(sourcePair)) continue;

                String humanDecision = latestHumanDecision(leftSource, rightSource);
                if ("NO_MATCH".equals(humanDecision)) {
                    return ClusterValidation.rejected("A human cannot-link decision exists across the clusters");
                }
                if ("MATCH".equals(humanDecision)) continue;

                ScoreResult result = scoring.score(
                        normalization.normalize(currentRevision(leftSource), normalizationVersion),
                        normalization.normalize(currentRevision(rightSource), normalizationVersion), rules);
                if (result.contradiction()) {
                    return ClusterValidation.rejected("Email and phone contradict across the clusters");
                }
                if (result.comparableFields() < 2) {
                    return ClusterValidation.rejected("A cross-cluster pair has insufficient evidence");
                }
                if (!"AUTO_MATCH".equals(result.decision())) {
                    return ClusterValidation.rejected("Not every cross-cluster pair independently auto-matches");
                }
            }
        }
        return ClusterValidation.accepted();
    }

    private String latestHumanDecision(UUID leftSource, UUID rightSource) {
        return jdbc.sql("""
                SELECT rd.decision FROM review_decisions rd
                JOIN review_items ri ON ri.id = rd.review_item_id
                JOIN match_candidates mc ON mc.id = ri.candidate_id
                JOIN source_record_revisions lr ON lr.id = mc.left_revision_id
                JOIN source_record_revisions rr ON rr.id = mc.right_revision_id
                WHERE ((lr.source_record_id = :left AND rr.source_record_id = :right)
                    OR (lr.source_record_id = :right AND rr.source_record_id = :left))
                ORDER BY rd.created_at DESC, rd.id DESC LIMIT 1
                """).param("left", leftSource).param("right", rightSource)
                .query(String.class).optional().orElse(null);
    }

    private UUID currentRevision(UUID sourceId) {
        return jdbc.sql("SELECT current_revision_id FROM source_records WHERE id = :id")
                .param("id", sourceId).query(UUID.class).single();
    }
}
