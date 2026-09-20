package com.masterdata.reconciliation.api.model;

import java.util.UUID;

public record IngestItemResult(
        String sourceRecordId,
        String status,
        UUID revisionId,
        int revisionNumber) {
}
