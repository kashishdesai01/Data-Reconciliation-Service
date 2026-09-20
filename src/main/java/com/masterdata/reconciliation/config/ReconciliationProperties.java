package com.masterdata.reconciliation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "reconciliation")
public record ReconciliationProperties(
        int scoringThreads,
        String reviewerUsername,
        String reviewerPassword,
        String defaultCountry) {
}

