package com.masterdata.reconciliation.service.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterdata.reconciliation.api.ApiException;
import com.masterdata.reconciliation.service.NormalizationService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GoldenProjectionService {
    private static final List<String> PROJECTED_FIELDS = List.of("fullName", "email", "phone", "address");

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final NormalizationService normalization;
    private final ReconciliationEventService events;

    public GoldenProjectionService(JdbcClient jdbc, ObjectMapper objectMapper,
                                   NormalizationService normalization, ReconciliationEventService events) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.normalization = normalization;
        this.events = events;
    }

    /** Rebuilds a golden version; the caller must already hold the golden-row lock. */
    public void recompute(UUID goldenId, UUID rulesetId, String actor, String reason, boolean appendEvent) {
        ProjectionRule rule = loadRule(rulesetId);
        List<MemberValue> members = loadMembers(goldenId);
        if (members.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "EMPTY_GOLDEN",
                    "Cannot project a golden record without members");
        }
        for (MemberValue member : members) {
            normalization.normalize(member.revisionId(), rule.normalizationVersion());
        }

        long version = currentVersion(goldenId) + 1;
        UUID versionId = UUID.randomUUID();
        Map<String, SelectedValue> selected = selectFields(members, rule);
        insertVersion(goldenId, versionId, version, selected);
        insertProvenance(versionId, selected);
        jdbc.sql("UPDATE golden_records SET version = :version, updated_at = now() WHERE id = :id")
                .param("version", version).param("id", goldenId).update();

        if (appendEvent) {
            Map<String, List<UUID>> membership = Map.of(goldenId.toString(), memberIds(goldenId));
            events.append("RECOMPUTE", goldenId, actor, reason, membership, membership,
                    Map.of("newVersion", version));
        }
    }

    private List<MemberValue> loadMembers(UUID goldenId) {
        return jdbc.sql("""
                SELECT sr.id source_id, sr.source_system, sr.source_record_id source_key,
                       rev.id revision_id, rev.raw_payload::text raw_payload,
                       rev.source_updated_at, rev.ingested_at
                FROM golden_memberships gm JOIN source_records sr ON sr.id = gm.source_record_id
                JOIN source_record_revisions rev ON rev.id = sr.current_revision_id
                WHERE gm.golden_record_id = :goldenId ORDER BY sr.id
                """).param("goldenId", goldenId).query((rs, rowNum) -> new MemberValue(
                        rs.getString("source_system"), rs.getString("source_key"),
                        rs.getObject("revision_id", UUID.class), readNode(rs.getString("raw_payload")),
                        instant(rs, "source_updated_at"), instant(rs, "ingested_at"))).list();
    }

    private Map<String, SelectedValue> selectFields(List<MemberValue> members, ProjectionRule rule) {
        Map<String, SelectedValue> selected = new LinkedHashMap<>();
        for (String field : PROJECTED_FIELDS) {
            List<String> priority = rule.priorities().getOrDefault(field, List.of());
            SelectedValue value = members.stream()
                    .map(member -> candidate(field, member, priority, rule.normalizationVersion()))
                    .filter(java.util.Objects::nonNull)
                    .min(Comparator.comparingInt(SelectedValue::priority)
                            .thenComparing((SelectedValue item) -> item.member().sourceUpdatedAt(),
                                    Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing((SelectedValue item) -> item.member().ingestedAt(), Comparator.reverseOrder())
                            .thenComparing(item -> item.member().sourceSystem())
                            .thenComparing(item -> item.member().sourceKey()))
                    .orElse(null);
            selected.put(field, value);
        }
        return selected;
    }

    private SelectedValue candidate(String field, MemberValue member, List<String> priority,
                                    int normalizationVersion) {
        JsonNode node = member.payload().get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) return null;
        String raw = node.asText();
        var normalizedRecord = normalization.find(member.revisionId(), normalizationVersion);
        String normalizedValue = switch (field) {
            case "fullName" -> normalizedRecord.fullName();
            case "email" -> normalizedRecord.email();
            case "phone" -> normalizedRecord.phoneE164();
            case "address" -> normalizedRecord.address();
            default -> null;
        };
        int rank = priority.indexOf(member.sourceSystem());
        if (rank < 0) rank = priority.size();
        String selectionRule = "sourcePriority=" + rank
                + "; updatedAt desc; ingestionTime desc; source identity asc";
        return new SelectedValue(member, raw, normalizedValue, rank, selectionRule);
    }

    private void insertVersion(UUID goldenId, UUID versionId, long version,
                               Map<String, SelectedValue> selected) {
        Map<String, String> normalizedValues = new LinkedHashMap<>();
        selected.forEach((field, value) ->
                normalizedValues.put(field, value == null ? null : value.normalized()));
        jdbc.sql("""
                INSERT INTO golden_record_versions
                    (id, golden_record_id, version, full_name, email, phone, address, normalized_values)
                VALUES (:id, :goldenId, :version, :fullName, :email, :phone, :address, CAST(:normalized AS jsonb))
                """).param("id", versionId).param("goldenId", goldenId).param("version", version)
                .param("fullName", raw(selected.get("fullName"))).param("email", raw(selected.get("email")))
                .param("phone", raw(selected.get("phone"))).param("address", raw(selected.get("address")))
                .param("normalized", write(normalizedValues)).update();
    }

    private void insertProvenance(UUID versionId, Map<String, SelectedValue> selected) {
        for (var entry : selected.entrySet()) {
            SelectedValue value = entry.getValue();
            if (value == null) continue;
            jdbc.sql("""
                    INSERT INTO golden_field_provenance
                        (id, golden_record_version_id, field_name, source_revision_id,
                         selection_rule, raw_value, normalized_value)
                    VALUES (:id, :versionId, :field, :revisionId, :rule, :raw, :normalized)
                    """).param("id", UUID.randomUUID()).param("versionId", versionId)
                    .param("field", entry.getKey()).param("revisionId", value.member().revisionId())
                    .param("rule", value.rule()).param("raw", value.raw())
                    .param("normalized", value.normalized()).update();
        }
    }

    private ProjectionRule loadRule(UUID rulesetId) {
        return jdbc.sql("SELECT normalization_version, config::text FROM rule_sets WHERE id = :id")
                .param("id", rulesetId).query((rs, rowNum) -> {
                    JsonNode config = readNode(rs.getString(2));
                    Map<String, List<String>> priorities = new HashMap<>();
                    config.path("sourcePriority").properties().forEach(entry -> {
                        List<String> order = new ArrayList<>();
                        entry.getValue().forEach(value -> order.add(value.asText()));
                        priorities.put(entry.getKey(), order);
                    });
                    return new ProjectionRule(rs.getInt(1), priorities);
                }).single();
    }

    private List<UUID> memberIds(UUID goldenId) {
        return jdbc.sql("SELECT source_record_id FROM golden_memberships WHERE golden_record_id = :id ORDER BY source_record_id")
                .param("id", goldenId).query(UUID.class).list();
    }

    private long currentVersion(UUID goldenId) {
        return jdbc.sql("SELECT version FROM golden_records WHERE id = :id")
                .param("id", goldenId).query(Long.class).single();
    }

    private static String raw(SelectedValue value) {
        return value == null ? null : value.raw();
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize golden projection", exception);
        }
    }

    private JsonNode readNode(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to deserialize source payload", exception);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record MemberValue(String sourceSystem, String sourceKey, UUID revisionId,
                               JsonNode payload, Instant sourceUpdatedAt, Instant ingestedAt) {
    }

    private record SelectedValue(MemberValue member, String raw, String normalized,
                                 int priority, String rule) {
    }

    private record ProjectionRule(int normalizationVersion, Map<String, List<String>> priorities) {
    }
}
