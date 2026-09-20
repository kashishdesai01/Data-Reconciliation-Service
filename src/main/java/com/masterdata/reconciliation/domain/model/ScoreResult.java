package com.masterdata.reconciliation.domain.model;

import java.util.Map;
import java.util.UUID;

public record ScoreResult(
        UUID leftRevisionId,
        UUID rightRevisionId,
        double score,
        int comparableFields,
        boolean contradiction,
        String decision,
        String reason,
        Map<String, Object> evidence) {
}
