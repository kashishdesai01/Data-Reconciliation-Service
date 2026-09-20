package com.masterdata.reconciliation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterdata.reconciliation.domain.model.CandidatePair;
import com.masterdata.reconciliation.domain.model.NormalizedRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class DeterminismTest {
    private final MatchScoringService scoring = new MatchScoringService();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void scoresAreEquivalentAcrossInputOrderAndPoolSizes() {
        List<InputPair> pairs = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            var left = record(i, "Person " + i, "person" + i + "@example.test", "+1415555" + String.format("%04d", i));
            var right = record(i + 1000, i % 8 == 0 ? "Persn " + i : "Person " + i,
                    i % 11 == 0 ? "other" + i + "@example.test" : left.email(), left.phoneE164());
            pairs.add(new InputPair(CandidatePair.of(left.revisionId(), right.revisionId()), left, right));
        }
        Map<String, String> baseline = evaluate(pairs, 1);
        Collections.shuffle(pairs, new java.util.Random(42));
        assertThat(evaluate(pairs, 4)).isEqualTo(baseline);
        Collections.reverse(pairs);
        assertThat(evaluate(pairs, 16)).isEqualTo(baseline);
    }

    private Map<String, String> evaluate(List<InputPair> pairs, int threads) {
        try (var executor = Executors.newFixedThreadPool(threads)) {
            var futures = pairs.stream().map(pair -> CompletableFuture.supplyAsync(() -> {
                var result = scoring.score(pair.left(), pair.right());
                return Map.entry(pair.id().toString(), result.decision() + ":" + result.score());
            }, executor)).toList();
            Map<String, String> result = new LinkedHashMap<>();
            futures.stream().map(CompletableFuture::join).sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
            return result;
        }
    }

    private NormalizedRecord record(int id, String name, String email, String phone) {
        UUID revision = UUID.nameUUIDFromBytes(("revision-" + id).getBytes());
        UUID source = UUID.nameUUIDFromBytes(("source-" + id).getBytes());
        var raw = mapper.createObjectNode().put("fullName", name).put("email", email).put("phone", phone)
                .put("address", id + " main st").put("postalCode", "90001");
        return new NormalizedRecord(revision, source, "CRM", "record-" + id,
                name.toLowerCase(), "person", Integer.toString(id), "P625", email, phone,
                phone.substring(phone.length() - 7), id + " main st", "90001", Integer.toString(id),
                Instant.EPOCH, Instant.EPOCH, raw);
    }

    private record InputPair(CandidatePair id, NormalizedRecord left, NormalizedRecord right) {}
}
