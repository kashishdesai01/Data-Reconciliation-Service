package com.masterdata.reconciliation.service.golden;

import com.masterdata.reconciliation.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class GoldenRecordQueryService {
    private final JdbcClient jdbc;
    private final ReconciliationEventService events;

    public GoldenRecordQueryService(JdbcClient jdbc, ReconciliationEventService events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    public Map<String, Object> get(UUID goldenId) {
        Map<String, Object> header = loadHeader(goldenId);
        Map<String, Object> result = new LinkedHashMap<>(header);
        result.put("members", loadMembers(goldenId));
        result.put("provenance", loadProvenance(goldenId, (long) header.get("version")));
        result.put("history", events.history(goldenId));
        return result;
    }

    private Map<String, Object> loadHeader(UUID goldenId) {
        return jdbc.sql("""
                SELECT gr.id, gr.version, gr.status, gr.merged_into_id, gr.created_at, gr.updated_at,
                       grv.full_name, grv.email, grv.phone, grv.address
                FROM golden_records gr
                LEFT JOIN golden_record_versions grv
                  ON grv.golden_record_id = gr.id AND grv.version = gr.version
                WHERE gr.id = :id
                """).param("id", goldenId).query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("version", rs.getLong("version"));
                    row.put("status", rs.getString("status"));
                    row.put("mergedIntoId", rs.getObject("merged_into_id", UUID.class));
                    row.put("fullName", rs.getString("full_name"));
                    row.put("email", rs.getString("email"));
                    row.put("phone", rs.getString("phone"));
                    row.put("address", rs.getString("address"));
                    row.put("createdAt", instant(rs, "created_at"));
                    row.put("updatedAt", instant(rs, "updated_at"));
                    return row;
                }).optional().orElseThrow(() ->
                        new ApiException(HttpStatus.NOT_FOUND, "GOLDEN_NOT_FOUND", "Golden record not found"));
    }

    private Object loadMembers(UUID goldenId) {
        return jdbc.sql("""
                SELECT sr.id, sr.source_system, sr.source_record_id, sr.current_revision_id
                FROM golden_memberships gm JOIN source_records sr ON sr.id = gm.source_record_id
                WHERE gm.golden_record_id = :id ORDER BY sr.source_system, sr.source_record_id
                """).param("id", goldenId).query((rs, rowNum) -> Map.of(
                        "id", rs.getObject("id", UUID.class),
                        "sourceSystem", rs.getString("source_system"),
                        "sourceRecordId", rs.getString("source_record_id"),
                        "currentRevisionId", rs.getObject("current_revision_id", UUID.class))).list();
    }

    private Object loadProvenance(UUID goldenId, long version) {
        return jdbc.sql("""
                SELECT gfp.field_name, gfp.source_revision_id, gfp.selection_rule,
                       gfp.raw_value, gfp.normalized_value
                FROM golden_field_provenance gfp
                JOIN golden_record_versions grv ON grv.id = gfp.golden_record_version_id
                WHERE grv.golden_record_id = :id AND grv.version = :version
                ORDER BY gfp.field_name
                """).param("id", goldenId).param("version", version).query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("field", rs.getString("field_name"));
                    row.put("sourceRevisionId", rs.getObject("source_revision_id", UUID.class));
                    row.put("selectionRule", rs.getString("selection_rule"));
                    row.put("rawValue", rs.getString("raw_value"));
                    row.put("normalizedValue", rs.getString("normalized_value"));
                    return row;
                }).list();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
