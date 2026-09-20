package com.masterdata.reconciliation.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record IngestRecordRequest(
        @NotBlank @Size(max = 200) String sourceRecordId,
        @Size(max = 500) String fullName,
        @Size(max = 500) String email,
        @Size(max = 100) String phone,
        @Size(max = 1000) String address,
        @Size(max = 40) String postalCode,
        Instant updatedAt) {
}
