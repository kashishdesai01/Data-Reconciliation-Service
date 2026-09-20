package com.masterdata.reconciliation.domain.model;

public record ClusterValidation(boolean allowed, String reason) {
    public static ClusterValidation accepted() {
        return new ClusterValidation(true, "Complete-link validation passed");
    }

    public static ClusterValidation rejected(String reason) {
        return new ClusterValidation(false, reason);
    }
}
