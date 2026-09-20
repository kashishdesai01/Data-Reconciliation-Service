package com.masterdata.reconciliation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class RulesetService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public RulesetService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Ruleset get(UUID id) {
        return jdbc.sql("SELECT version, normalization_version, config::text FROM rule_sets WHERE id = :id")
                .param("id", id).query((rs, n) -> parse(id, rs.getString("version"),
                        rs.getInt("normalization_version"), rs.getString("config"))).single();
    }

    private Ruleset parse(UUID id, String version, int normalizationVersion, String json) {
        try {
            JsonNode config = objectMapper.readTree(json);
            Map<String, Double> weights = new LinkedHashMap<>();
            for (String field : new String[]{"email", "phone", "name", "address"}) {
                weights.put(field, config.path("weights").path(field).asDouble());
            }
            JsonNode thresholds = config.path("thresholds");
            return new Ruleset(id, version, normalizationVersion, Map.copyOf(weights),
                    thresholds.path("autoMatch").asDouble(), thresholds.path("review").asDouble(),
                    thresholds.path("nameWithIdentifier").asDouble(),
                    thresholds.path("nameWithBothIdentifiers").asDouble(),
                    config.path("maxBlockSize").asInt(), config.path("maxClusterSize").asInt());
        } catch (Exception e) {
            throw new IllegalStateException("Ruleset configuration is invalid: " + version, e);
        }
    }

    public record Ruleset(UUID id, String version, int normalizationVersion,
                          Map<String, Double> weights, double autoMatchThreshold,
                          double reviewThreshold, double nameWithIdentifierThreshold,
                          double nameWithBothIdentifiersThreshold, int maxBlockSize,
                          int maxClusterSize) {
        public static Ruleset defaults() {
            return new Ruleset(new UUID(0, 1), "v1", 1,
                    Map.of("email", 0.35, "phone", 0.30, "name", 0.20, "address", 0.15),
                    0.90, 0.72, 0.92, 0.80, 500, 20);
        }
    }
}

