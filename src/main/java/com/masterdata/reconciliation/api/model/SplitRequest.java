package com.masterdata.reconciliation.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record SplitRequest(
        @NotEmpty List<UUID> sourceRecordIds,
        @NotBlank @Size(max = 2000) String reason,
        @NotNull Long expectedVersion) {
}
