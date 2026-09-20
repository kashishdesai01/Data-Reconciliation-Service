package com.masterdata.reconciliation.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration
public class MetricsConfig {
    public MetricsConfig(MeterRegistry registry, JdbcClient jdbc) {
        gauge(registry, jdbc, "reconciliation.runs.active", "Runs currently starting or running",
                "SELECT count(*) FROM match_runs WHERE status IN ('STARTING','RUNNING')");
        gauge(registry, jdbc, "reconciliation.review.backlog", "Pending human review items",
                "SELECT count(*) FROM review_items WHERE status = 'PENDING'");
        gauge(registry, jdbc, "reconciliation.apply.failures", "Total candidate apply failures",
                "SELECT COALESCE(sum(apply_failures),0) FROM match_runs");
        gauge(registry, jdbc, "reconciliation.apply.retries", "Total transient apply retries",
                "SELECT COALESCE(sum(retry_count),0) FROM match_runs");
        gauge(registry, jdbc, "reconciliation.block.max.size", "Largest observed block in the latest run",
                "SELECT COALESCE(max(max_observed_block_size),0) FROM match_runs");
    }

    private void gauge(MeterRegistry registry, JdbcClient jdbc, String name, String description, String sql) {
        Gauge.builder(name, jdbc, source -> source.sql(sql).query(Double.class).single())
                .description(description).register(registry);
    }
}
