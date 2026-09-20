package com.masterdata.reconciliation.domain.model;

import java.util.UUID;

/** A database-compatible canonical UUID pair. */
public record CandidatePair(UUID left, UUID right) implements Comparable<CandidatePair> {
    public static CandidatePair of(UUID first, UUID second) {
        return first.toString().compareTo(second.toString()) <= 0
                ? new CandidatePair(first, second)
                : new CandidatePair(second, first);
    }

    @Override
    public int compareTo(CandidatePair other) {
        int leftOrder = left.toString().compareTo(other.left.toString());
        return leftOrder != 0 ? leftOrder : right.toString().compareTo(other.right.toString());
    }
}
