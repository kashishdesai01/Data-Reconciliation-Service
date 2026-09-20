package com.masterdata.reconciliation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterdata.reconciliation.api.ApiException;
import com.masterdata.reconciliation.api.model.IngestBatchRequest;
import com.masterdata.reconciliation.api.model.IngestBatchResponse;
import com.masterdata.reconciliation.api.model.IngestItemResult;
import com.masterdata.reconciliation.api.model.IngestRecordRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.sql.Timestamp;

@Service
public class IngestionService {
    private final JdbcClient jdbc;
    private final CanonicalJson canonicalJson;
    private final ObjectMapper objectMapper;
    private final GoldenRecordService goldens;

    public IngestionService(JdbcClient jdbc, CanonicalJson canonicalJson, ObjectMapper objectMapper,
                            GoldenRecordService goldens) {
        this.jdbc = jdbc;
        this.canonicalJson = canonicalJson;
        this.objectMapper = objectMapper;
        this.goldens = goldens;
    }

    @Transactional
    public IngestBatchResponse ingest(String sourceSystem, IngestBatchRequest request) {
        String source = sourceSystem == null ? "" : sourceSystem.trim();
        if (source.isEmpty() || source.length() > 100 || !source.matches("[A-Za-z0-9_-]+")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_SYSTEM",
                    "sourceSystem must contain only letters, digits, underscore, or hyphen");
        }
        var seen = new HashSet<String>();
        for (var item : request.records()) {
            if (!seen.add(item.sourceRecordId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_BATCH_ID",
                        "sourceRecordId appears more than once in the batch: " + item.sourceRecordId());
            }
        }

        int created = 0;
        int unchanged = 0;
        List<IngestItemResult> results = new ArrayList<>();
        var affectedGoldens = new LinkedHashSet<UUID>();
        for (var item : request.records()) {
            var payload = new Payload(item.fullName(), item.email(), item.phone(), item.address(),
                    item.postalCode(), item.updatedAt());
            String hash = canonicalJson.hash(payload);
            UUID sourceId = ensureAndLockSource(source, item.sourceRecordId());
            var current = jdbc.sql("""
                    SELECT rev.id, rev.payload_hash, rev.revision_number
                    FROM source_records sr LEFT JOIN source_record_revisions rev ON rev.id = sr.current_revision_id
                    WHERE sr.id = :sourceId
                    """).param("sourceId", sourceId).query((rs, n) -> new CurrentRevision(
                    rs.getObject("id", UUID.class), rs.getString("payload_hash"), rs.getInt("revision_number"))).single();
            if (hash.equals(current.hash())) {
                unchanged++;
                results.add(new IngestItemResult(item.sourceRecordId(), "UNCHANGED", current.id(), current.number()));
                continue;
            }

            UUID revisionId = UUID.randomUUID();
            int revisionNumber = current.id() == null ? 1 : current.number() + 1;
            String json = write(canonicalJson.tree(payload));
            jdbc.sql("""
                    INSERT INTO source_record_revisions
                        (id, source_record_id, revision_number, payload_hash, raw_payload, source_updated_at)
                    VALUES (:id, :sourceId, :revisionNumber, :hash, CAST(:payload AS jsonb), :updatedAt)
                    """).param("id", revisionId).param("sourceId", sourceId).param("revisionNumber", revisionNumber)
                    .param("hash", hash).param("payload", json)
                    .param("updatedAt", item.updatedAt() == null ? null : Timestamp.from(item.updatedAt())).update();
            jdbc.sql("UPDATE source_records SET current_revision_id = :revisionId WHERE id = :sourceId")
                    .param("revisionId", revisionId).param("sourceId", sourceId).update();
            UUID linkedGolden = goldens.membership(sourceId);
            if (linkedGolden != null) affectedGoldens.add(linkedGolden);
            created++;
            results.add(new IngestItemResult(item.sourceRecordId(), "CREATED", revisionId, revisionNumber));
        }
        if (!affectedGoldens.isEmpty()) {
            UUID rulesetId = jdbc.sql("SELECT id FROM rule_sets WHERE active").query(UUID.class).single();
            for (UUID goldenId : affectedGoldens) {
                goldens.recompute(goldenId, rulesetId, "system", "Source record revision changed");
            }
        }
        return new IngestBatchResponse(created, unchanged, List.copyOf(results));
    }

    private UUID ensureAndLockSource(String sourceSystem, String sourceRecordKey) {
        UUID proposed = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO source_records (id, source_system, source_record_id)
                    VALUES (:id, :sourceSystem, :sourceRecordKey)
                    ON CONFLICT (source_system, source_record_id) DO NOTHING
                    """).param("id", proposed).param("sourceSystem", sourceSystem)
                    .param("sourceRecordKey", sourceRecordKey).update();
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "INGESTION_CONFLICT", "Concurrent ingestion conflict; retry the batch");
        }
        return jdbc.sql("""
                SELECT id FROM source_records WHERE source_system = :sourceSystem
                AND source_record_id = :sourceRecordKey FOR UPDATE
                """).param("sourceSystem", sourceSystem).param("sourceRecordKey", sourceRecordKey)
                .query(UUID.class).single();
    }

    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Unable to serialize payload", e); }
    }

    private record CurrentRevision(UUID id, String hash, int number) {}
    private record Payload(String fullName, String email, String phone, String address,
                           String postalCode, java.time.Instant updatedAt) {}
}
