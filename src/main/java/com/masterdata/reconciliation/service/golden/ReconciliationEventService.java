package com.masterdata.reconciliation.service.golden;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReconciliationEventService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ReconciliationEventService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void append(String type, UUID goldenId, String actor, String reason,
                       Object before, Object after, Object details) {
        jdbc.sql("""
                INSERT INTO reconciliation_events
                    (id, event_type, golden_record_id, actor, reason, before_memberships, after_memberships, details)
                VALUES (:id, :type, :goldenId, :actor, :reason,
                    CAST(:before AS jsonb), CAST(:after AS jsonb), CAST(:details AS jsonb))
                """).param("id", UUID.randomUUID()).param("type", type).param("goldenId", goldenId)
                .param("actor", actor).param("reason", reason).param("before", write(before))
                .param("after", write(after)).param("details", write(details)).update();
    }

    public List<Map<String, Object>> history(UUID goldenId) {
        return jdbc.sql("""
                SELECT id, event_type, actor, reason, before_memberships::text, after_memberships::text,
                       details::text, created_at FROM reconciliation_events
                WHERE golden_record_id = :id OR details->>'absorbedGoldenId' = :idText
                   OR details->>'newGoldenId' = :idText
                ORDER BY created_at, id
                """).param("id", goldenId).param("idText", goldenId.toString()).query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("type", rs.getString("event_type"));
                    row.put("actor", rs.getString("actor"));
                    row.put("reason", rs.getString("reason"));
                    row.put("before", readMap(rs.getString("before_memberships")));
                    row.put("after", readMap(rs.getString("after_memberships")));
                    row.put("details", readMap(rs.getString("details")));
                    row.put("createdAt", instant(rs, "created_at"));
                    return row;
                }).list();
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize reconciliation event", exception);
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to deserialize reconciliation event", exception);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
