package com.masterdata.reconciliation.service;

import com.masterdata.reconciliation.api.ApiException;
import com.masterdata.reconciliation.api.model.SplitRequest;
import com.masterdata.reconciliation.service.golden.GoldenProjectionService;
import com.masterdata.reconciliation.service.golden.GoldenRecordQueryService;
import com.masterdata.reconciliation.service.golden.ReconciliationEventService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Coordinates golden membership changes; projection, reads, and audit events are delegated. */
@Service
public class GoldenRecordService {
    private final JdbcClient jdbc;
    private final GoldenProjectionService projection;
    private final GoldenRecordQueryService queries;
    private final ReconciliationEventService events;

    public GoldenRecordService(JdbcClient jdbc, GoldenProjectionService projection,
                               GoldenRecordQueryService queries, ReconciliationEventService events) {
        this.jdbc = jdbc;
        this.projection = projection;
        this.queries = queries;
        this.events = events;
    }

    @Transactional
    public UUID ensureSingleton(UUID sourceRecordId, UUID rulesetId) {
        UUID existing = membership(sourceRecordId);
        if (existing != null) return existing;

        UUID goldenId = UUID.randomUUID();
        jdbc.sql("INSERT INTO golden_records (id, status) VALUES (:id, 'ACTIVE')")
                .param("id", goldenId).update();
        int inserted = jdbc.sql("""
                INSERT INTO golden_memberships (source_record_id, golden_record_id)
                VALUES (:sourceId, :goldenId) ON CONFLICT (source_record_id) DO NOTHING
                """).param("sourceId", sourceRecordId).param("goldenId", goldenId).update();
        if (inserted == 0) {
            jdbc.sql("DELETE FROM golden_records WHERE id = :id AND version = 0")
                    .param("id", goldenId).update();
            return membership(sourceRecordId);
        }
        projection.recompute(goldenId, rulesetId, "system", "Initial singleton projection", false);
        return goldenId;
    }

    @Transactional
    public UUID merge(UUID leftSourceId, UUID rightSourceId, UUID rulesetId, String actor, String reason) {
        UUID leftGolden = ensureSingleton(leftSourceId, rulesetId);
        UUID rightGolden = ensureSingleton(rightSourceId, rulesetId);
        if (leftGolden.equals(rightGolden)) return leftGolden;

        List<GoldenAge> lockedGoldens = lockGoldens(leftGolden, rightGolden);
        if (lockedGoldens.size() != 2) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_MEMBERSHIP", "Golden membership changed");
        }
        UUID survivor = lockedGoldens.stream()
                .min(Comparator.comparing(GoldenAge::createdAt).thenComparing(GoldenAge::id))
                .orElseThrow().id();
        UUID absorbed = survivor.equals(leftGolden) ? rightGolden : leftGolden;
        List<UUID> beforeSurvivor = members(survivor);
        List<UUID> beforeAbsorbed = members(absorbed);

        jdbc.sql("UPDATE golden_memberships SET golden_record_id = :survivor WHERE golden_record_id = :absorbed")
                .param("survivor", survivor).param("absorbed", absorbed).update();
        jdbc.sql("""
                UPDATE golden_records SET status = 'MERGED', merged_into_id = :survivor, updated_at = now()
                WHERE id = :absorbed AND status = 'ACTIVE'
                """).param("survivor", survivor).param("absorbed", absorbed).update();
        projection.recompute(survivor, rulesetId, actor, reason, false);
        events.append("MERGE", survivor, actor, reason,
                Map.of(survivor.toString(), beforeSurvivor, absorbed.toString(), beforeAbsorbed),
                Map.of(survivor.toString(), members(survivor)),
                Map.of("absorbedGoldenId", absorbed));
        return survivor;
    }

    @Transactional
    public Map<String, Object> split(UUID goldenId, SplitRequest request, String actor) {
        LockedGolden golden = lockActiveGolden(goldenId);
        if (golden.version() != request.expectedVersion()) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_GOLDEN_VERSION",
                    "Golden record changed; refresh before splitting");
        }
        List<UUID> before = members(goldenId);
        List<UUID> requested = request.sourceRecordIds().stream().distinct().toList();
        validateSplitMembership(before, requested, request.sourceRecordIds().size());

        UUID rulesetId = activeRulesetId();
        UUID newGoldenId = UUID.randomUUID();
        jdbc.sql("INSERT INTO golden_records (id, status) VALUES (:id, 'ACTIVE')")
                .param("id", newGoldenId).update();
        jdbc.sql("""
                UPDATE golden_memberships SET golden_record_id = :newGolden
                WHERE golden_record_id = :oldGolden AND source_record_id IN (:members)
                """).param("newGolden", newGoldenId).param("oldGolden", goldenId)
                .param("members", requested).update();
        projection.recompute(goldenId, rulesetId, actor, request.reason(), false);
        projection.recompute(newGoldenId, rulesetId, actor, request.reason(), false);
        events.append("SPLIT", goldenId, actor, request.reason(),
                Map.of(goldenId.toString(), before),
                Map.of(goldenId.toString(), members(goldenId),
                        newGoldenId.toString(), members(newGoldenId)),
                Map.of("newGoldenId", newGoldenId));
        return Map.of("originalGoldenId", goldenId, "newGoldenId", newGoldenId,
                "originalVersion", currentVersion(goldenId), "newVersion", currentVersion(newGoldenId));
    }

    @Transactional
    public void recompute(UUID goldenId, UUID rulesetId, String actor, String reason) {
        jdbc.sql("SELECT id FROM golden_records WHERE id = :id AND status = 'ACTIVE' FOR UPDATE")
                .param("id", goldenId).query(UUID.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "GOLDEN_NOT_ACTIVE",
                        "Golden record is not active"));
        projection.recompute(goldenId, rulesetId, actor, reason, true);
    }

    public Map<String, Object> get(UUID goldenId) {
        return queries.get(goldenId);
    }

    public UUID membership(UUID sourceRecordId) {
        return jdbc.sql("SELECT golden_record_id FROM golden_memberships WHERE source_record_id = :id")
                .param("id", sourceRecordId).query(UUID.class).optional().orElse(null);
    }

    public List<UUID> members(UUID goldenId) {
        return jdbc.sql("SELECT source_record_id FROM golden_memberships WHERE golden_record_id = :id ORDER BY source_record_id")
                .param("id", goldenId).query(UUID.class).list();
    }

    private List<GoldenAge> lockGoldens(UUID leftGolden, UUID rightGolden) {
        return jdbc.sql("""
                SELECT id, created_at FROM golden_records WHERE id IN (:left, :right)
                ORDER BY id FOR UPDATE
                """).param("left", leftGolden).param("right", rightGolden)
                .query((rs, rowNum) -> new GoldenAge(
                        rs.getObject("id", UUID.class), instant(rs, "created_at"))).list();
    }

    private LockedGolden lockActiveGolden(UUID goldenId) {
        LockedGolden golden = jdbc.sql("SELECT version, status FROM golden_records WHERE id = :id FOR UPDATE")
                .param("id", goldenId).query((rs, rowNum) ->
                        new LockedGolden(rs.getLong("version"), rs.getString("status")))
                .optional().orElseThrow(() ->
                        new ApiException(HttpStatus.NOT_FOUND, "GOLDEN_NOT_FOUND", "Golden record not found"));
        if (!"ACTIVE".equals(golden.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "GOLDEN_NOT_ACTIVE",
                    "Only an active golden record can be split");
        }
        return golden;
    }

    private void validateSplitMembership(List<UUID> current, List<UUID> requested, int originalRequestSize) {
        if (requested.size() != originalRequestSize) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_SPLIT_MEMBER",
                    "Split member IDs must be unique");
        }
        if (!current.containsAll(requested)) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_MEMBERSHIP",
                    "One or more selected records are no longer members");
        }
        if (requested.size() == current.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_ORIGINAL_GOLDEN",
                    "A split cannot move every member");
        }
    }

    private UUID activeRulesetId() {
        return jdbc.sql("SELECT id FROM rule_sets WHERE active").query(UUID.class).single();
    }

    private long currentVersion(UUID goldenId) {
        return jdbc.sql("SELECT version FROM golden_records WHERE id = :id")
                .param("id", goldenId).query(Long.class).single();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record GoldenAge(UUID id, Instant createdAt) {
    }

    private record LockedGolden(long version, String status) {
    }
}
