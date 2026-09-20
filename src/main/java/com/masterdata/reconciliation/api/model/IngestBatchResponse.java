package com.masterdata.reconciliation.api.model;

import java.util.List;

public record IngestBatchResponse(
        int created,
        int unchanged,
        List<IngestItemResult> items) {
}
