package com.masterdata.reconciliation.domain.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record NormalizedRecord(
        UUID revisionId,
        UUID sourceRecordId,
        String sourceSystem,
        String sourceRecordKey,
        String fullName,
        String firstName,
        String surname,
        String surnameSoundex,
        String email,
        String phoneE164,
        String phoneLast7,
        String address,
        String postalCode,
        String streetNumber,
        Instant sourceUpdatedAt,
        Instant ingestedAt,
        JsonNode rawPayload) {
}
