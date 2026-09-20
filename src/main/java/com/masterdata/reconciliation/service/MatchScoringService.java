package com.masterdata.reconciliation.service;

import com.masterdata.reconciliation.domain.model.NormalizedRecord;
import com.masterdata.reconciliation.domain.model.ScoreResult;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

import com.masterdata.reconciliation.service.RulesetService.Ruleset;

@Service
public class MatchScoringService {
    private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();
    private static final LevenshteinDistance LEVENSHTEIN = LevenshteinDistance.getDefaultInstance();
    public ScoreResult score(NormalizedRecord left, NormalizedRecord right) {
        return score(left, right, Ruleset.defaults());
    }

    public ScoreResult score(NormalizedRecord left, NormalizedRecord right, Ruleset rules) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        double weighted = 0;
        double comparableWeight = 0;
        int comparable = 0;

        Double email = exact(left.email(), right.email());
        Double phone = exact(left.phoneE164(), right.phoneE164());
        Double name = similarity(left.fullName(), right.fullName(), "name");
        Double address = similarity(left.address(), right.address(), "address");
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("email", email);
        values.put("phone", phone);
        values.put("name", name);
        values.put("address", address);

        for (String field : rules.weights().keySet().stream().sorted().toList()) {
            Double value = values.get(field);
            Map<String, Object> fieldEvidence = new LinkedHashMap<>();
            fieldEvidence.put("leftRaw", raw(left, field));
            fieldEvidence.put("rightRaw", raw(right, field));
            fieldEvidence.put("leftNormalized", normalized(left, field));
            fieldEvidence.put("rightNormalized", normalized(right, field));
            fieldEvidence.put("comparable", value != null);
            fieldEvidence.put("similarity", value);
            evidence.put(field, fieldEvidence);
            if (value != null) {
                comparable++;
                comparableWeight += rules.weights().get(field);
                weighted += value * rules.weights().get(field);
            }
        }

        double score = comparableWeight == 0 ? 0 : weighted / comparableWeight;
        boolean contradiction = different(left.email(), right.email()) && different(left.phoneE164(), right.phoneE164());
        String decision;
        String reason;
        if (comparable < 2) {
            decision = "INSUFFICIENT_EVIDENCE";
            reason = "Fewer than two fields are comparable";
        } else if (contradiction) {
            decision = "REVIEW";
            reason = "Email and phone both contradict";
        } else if (autoMatch(score, false, email, phone, name, rules)) {
            decision = "AUTO_MATCH";
            reason = exactOne(email, phone)
                    ? "Exact identifier with strong name agreement"
                    : "Both identifiers exact with sufficient name agreement";
        } else if (score >= rules.reviewThreshold()) {
            decision = "REVIEW";
            reason = "Score meets review threshold but not auto-match safeguards";
        } else {
            decision = "NO_MATCH";
            reason = "Score is below review threshold";
        }
        evidence.put("comparableFields", comparable);
        evidence.put("contradiction", contradiction);
        evidence.put("rulesetVersion", rules.version());
        return new ScoreResult(left.revisionId(), right.revisionId(), score, comparable,
                contradiction, decision, reason, Map.copyOf(evidence));
    }

    private boolean autoMatch(double score, boolean contradiction, Double email, Double phone, Double name, Ruleset rules) {
        if (score < rules.autoMatchThreshold() || contradiction || name == null) return false;
        boolean oneIdentifierAndName = exactOne(email, phone) && name >= rules.nameWithIdentifierThreshold();
        boolean bothIdentifiersAndName = isExact(email) && isExact(phone) && name >= rules.nameWithBothIdentifiersThreshold();
        return oneIdentifierAndName || bothIdentifiersAndName;
    }

    private boolean exactOne(Double email, Double phone) { return isExact(email) || isExact(phone); }
    private boolean isExact(Double value) { return value != null && value == 1.0; }

    private Double similarity(String left, String right, String field) {
        if (left == null || right == null) return null;
        if (field.equals("name")) return JARO_WINKLER.apply(left, right);
        if (left.equals(right)) return 1.0;
        int max = Math.max(left.length(), right.length());
        return max == 0 ? 1.0 : Math.max(0.0, 1.0 - ((double) LEVENSHTEIN.apply(left, right) / max));
    }

    private Double exact(String left, String right) {
        return left == null || right == null ? null : (left.equals(right) ? 1.0 : 0.0);
    }

    private boolean different(String left, String right) {
        return left != null && right != null && !left.equals(right);
    }

    private String normalized(NormalizedRecord record, String field) {
        return switch (field) {
            case "email" -> record.email();
            case "phone" -> record.phoneE164();
            case "name" -> record.fullName();
            case "address" -> record.address();
            default -> null;
        };
    }

    private String raw(NormalizedRecord record, String field) {
        String rawField = switch (field) {
            case "name" -> "fullName";
            default -> field;
        };
        var value = record.rawPayload().get(rawField);
        return value == null || value.isNull() ? null : value.asText();
    }

}
