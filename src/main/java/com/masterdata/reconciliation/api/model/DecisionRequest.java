package com.masterdata.reconciliation.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record DecisionRequest(
        @NotBlank String decision,
        @NotBlank @Size(max = 200) String idempotencyKey,
        @NotNull Long expectedVersion,
        @Size(max = 2000) String reason,
        UUID supersedesDecisionId) {
}
