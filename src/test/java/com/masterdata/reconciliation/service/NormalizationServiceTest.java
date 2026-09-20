package com.masterdata.reconciliation.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizationServiceTest {
    @Test
    void normalizesUnicodePunctuationAndWhitespaceDeterministically() {
        assertThat(NormalizationService.normalizeName("  Jane—D’OE  ")).isEqualTo("jane d oe");
        assertThat(NormalizationService.normalizeAddress("42 Main Street, Apartment #5"))
                .isEqualTo("42 main st apt #5");
    }

    @Test
    void computesStableSurnameSoundex() {
        assertThat(NormalizationService.soundex("Smith")).isEqualTo("S530");
        assertThat(NormalizationService.soundex("Smyth")).isEqualTo("S530");
        assertThat(NormalizationService.soundex(null)).isNull();
    }
}

