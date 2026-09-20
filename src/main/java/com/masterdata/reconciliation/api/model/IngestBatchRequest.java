package com.masterdata.reconciliation.api.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record IngestBatchRequest(
        @NotEmpty @Size(max = 1000) List<@Valid IngestRecordRequest> records) {
}
