package com.masterdata.reconciliation.service;

import com.masterdata.reconciliation.domain.model.CandidatePair;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PairTest {
    @Test
    void canonicalPairIsSymmetric() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertThat(CandidatePair.of(a, b))
                .isEqualTo(CandidatePair.of(b, a));
        var pair = CandidatePair.of(a, b);
        assertThat(pair.left().toString()).isLessThan(pair.right().toString());
    }
}
