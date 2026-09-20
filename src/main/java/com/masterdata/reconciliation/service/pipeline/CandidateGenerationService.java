package com.masterdata.reconciliation.service.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterdata.reconciliation.domain.model.CandidatePair;
import com.masterdata.reconciliation.domain.model.NormalizedRecord;
import com.masterdata.reconciliation.service.NormalizationService;
import com.masterdata.reconciliation.service.RulesetService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class CandidateGenerationService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final MatchRunLifecycleService runs;
    private final NormalizationService normalization;
    private final RulesetService rulesets;

    public CandidateGenerationService(JdbcClient jdbc, ObjectMapper objectMapper,
                                      MatchRunLifecycleService runs, NormalizationService normalization,
                                      RulesetService rulesets) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.runs = runs;
        this.normalization = normalization;
        this.rulesets = rulesets;
    }

    @Transactional
    public void generate(UUID runId) {
        runs.stage(runId, "BLOCK_AND_DEDUPLICATE");
        UUID rulesetId = runs.rulesetId(runId);
        var rules = rulesets.get(rulesetId);
        List<NormalizedRecord> records = currentRecords(rules.normalizationVersion());
        Set<UUID> claimed = new HashSet<>(claimedRevisions(runId));
        if (claimed.isEmpty()) return;

        GenerationResult result = buildPairs(records, claimed, rules.maxBlockSize());
        persistPairs(runId, rulesetId, result.pairs());
        jdbc.sql("""
                UPDATE match_runs SET oversized_blocks = :oversized,
                    max_observed_block_size = :maxBlockSize WHERE id = :runId
                """).param("oversized", result.oversizedBlocks())
                .param("maxBlockSize", result.maxObservedBlockSize()).param("runId", runId).update();
    }

    private List<NormalizedRecord> currentRecords(int normalizationVersion) {
        List<UUID> revisions = jdbc.sql("""
                SELECT current_revision_id FROM source_records
                WHERE current_revision_id IS NOT NULL ORDER BY current_revision_id
                """).query(UUID.class).list();
        List<NormalizedRecord> records = new ArrayList<>(revisions.size());
        for (UUID revisionId : revisions) {
            records.add(normalization.normalize(revisionId, normalizationVersion));
        }
        return records;
    }

    private GenerationResult buildPairs(List<NormalizedRecord> records, Set<UUID> claimed, int maxBlockSize) {
        Map<CandidatePair, Set<String>> pairs = new TreeMap<>();
        int oversizedBlocks = 0;
        int maxObservedBlockSize = 0;
        for (BlockingRule rule : BlockingRule.values()) {
            Map<String, List<NormalizedRecord>> blocks = groupByBlock(records, rule);
            for (List<NormalizedRecord> values : blocks.values()) {
                maxObservedBlockSize = Math.max(maxObservedBlockSize, values.size());
                if (values.size() > maxBlockSize) {
                    oversizedBlocks++;
                    continue;
                }
                addPairs(values, claimed, rule.label, pairs);
            }
        }
        return new GenerationResult(pairs, oversizedBlocks, maxObservedBlockSize);
    }

    private Map<String, List<NormalizedRecord>> groupByBlock(List<NormalizedRecord> records, BlockingRule rule) {
        Map<String, List<NormalizedRecord>> blocks = new HashMap<>();
        for (NormalizedRecord record : records) {
            String key = rule.key(record);
            if (key != null) blocks.computeIfAbsent(key, ignored -> new ArrayList<>()).add(record);
        }
        return blocks;
    }

    private void addPairs(List<NormalizedRecord> values, Set<UUID> claimed, String rule,
                          Map<CandidatePair, Set<String>> pairs) {
        values.sort(Comparator.comparing(NormalizedRecord::revisionId));
        for (int leftIndex = 0; leftIndex < values.size(); leftIndex++) {
            for (int rightIndex = leftIndex + 1; rightIndex < values.size(); rightIndex++) {
                UUID left = values.get(leftIndex).revisionId();
                UUID right = values.get(rightIndex).revisionId();
                if (!claimed.contains(left) && !claimed.contains(right)) continue;
                pairs.computeIfAbsent(CandidatePair.of(left, right), ignored -> new TreeSet<>()).add(rule);
            }
        }
    }

    private void persistPairs(UUID runId, UUID rulesetId, Map<CandidatePair, Set<String>> pairs) {
        for (var entry : pairs.entrySet()) {
            CandidatePair pair = entry.getKey();
            jdbc.sql("""
                    INSERT INTO match_candidates
                        (id, ruleset_id, first_run_id, left_revision_id, right_revision_id, blocking_rules)
                    VALUES (:id, :ruleset, :runId, :left, :right, CAST(:rules AS jsonb))
                    ON CONFLICT (ruleset_id, left_revision_id, right_revision_id) DO UPDATE
                    SET blocking_rules = (
                        SELECT jsonb_agg(rule ORDER BY rule)
                        FROM (SELECT DISTINCT jsonb_array_elements_text(
                            match_candidates.blocking_rules || EXCLUDED.blocking_rules) AS rule) merged_rules)
                    """).param("id", UUID.randomUUID()).param("ruleset", rulesetId).param("runId", runId)
                    .param("left", pair.left()).param("right", pair.right())
                    .param("rules", write(entry.getValue())).update();
        }
    }

    private List<UUID> claimedRevisions(UUID runId) {
        return jdbc.sql("SELECT source_revision_id FROM match_run_items WHERE run_id = :id ORDER BY source_revision_id")
                .param("id", runId).query(UUID.class).list();
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize blocking rules", exception);
        }
    }

    private record GenerationResult(Map<CandidatePair, Set<String>> pairs, int oversizedBlocks,
                                    int maxObservedBlockSize) {
    }
}
