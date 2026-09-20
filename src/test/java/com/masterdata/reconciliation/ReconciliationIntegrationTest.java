package com.masterdata.reconciliation;

import com.masterdata.reconciliation.api.model.DecisionRequest;
import com.masterdata.reconciliation.api.model.IngestBatchRequest;
import com.masterdata.reconciliation.api.model.IngestRecordRequest;
import com.masterdata.reconciliation.api.model.SplitRequest;
import com.masterdata.reconciliation.service.GoldenRecordService;
import com.masterdata.reconciliation.service.IngestionService;
import com.masterdata.reconciliation.service.ReconciliationPipelineService;
import com.masterdata.reconciliation.service.ReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {
        "spring.batch.jdbc.initialize-schema=always",
        "reconciliation.scoring-threads=4"
})
class ReconciliationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("reconciliation").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired IngestionService ingestion;
    @Autowired ReconciliationPipelineService pipeline;
    @Autowired GoldenRecordService goldens;
    @Autowired JdbcClient jdbc;
    @Autowired ReviewService reviews;

    @Test
    void replayIsIdempotentAndDuplicatePairBuildsOneGolden() {
        String suffix = UUID.randomUUID().toString();
        var crm = record("crm-" + suffix, "Ana Rivera", "ana." + suffix + "@example.com", "+14155550111", "10 Pine Street", "94105");
        var billing = record("bill-" + suffix, "Ana Rivera", "ana." + suffix + "@example.com", "+14155550111", "10 Pine St", "94105");
        var first = ingestion.ingest("CRM", new IngestBatchRequest(List.of(crm)));
        var replay = ingestion.ingest("CRM", new IngestBatchRequest(List.of(crm)));
        ingestion.ingest("Billing", new IngestBatchRequest(List.of(billing)));
        assertThat(first.created()).isEqualTo(1);
        assertThat(replay.unchanged()).isEqualTo(1);

        UUID run = executeRun();
        assertThat(pipeline.runStatus(run).get("status")).isEqualTo("COMPLETED");
        UUID leftSource = source("CRM", crm.sourceRecordId());
        UUID rightSource = source("Billing", billing.sourceRecordId());
        assertThat(goldens.membership(leftSource)).isEqualTo(goldens.membership(rightSource));
        assertThat(goldens.get(goldens.membership(leftSource)).get("provenance")).asList().isNotEmpty();
    }

    @Test
    void completeLinkPreventsUnsafeTransitiveUnion() {
        String suffix = UUID.randomUUID().toString();
        String email = "transitive." + suffix + "@example.com";
        String phone = "+14155551234";
        var a = record("a-" + suffix, "John Smith", email, null, "42 Main Street", "10001");
        var b = record("b-" + suffix, "John Smith", email, phone, "42 Main St", "10001");
        var c = record("c-" + suffix, "John Smith", null, phone, "42 Main St", "10001");
        ingestion.ingest("CRM", new IngestBatchRequest(List.of(a, b, c)));

        UUID run = executeRun();
        long distinctGoldens = List.of(a, b, c).stream().map(item -> goldens.membership(source("CRM", item.sourceRecordId())))
                .distinct().count();
        assertThat(distinctGoldens).isEqualTo(2);
        Integer conflicts = jdbc.sql("""
                SELECT count(*) FROM review_items WHERE run_id = :run AND type = 'CLUSTER_CONFLICT'
                """).param("run", run).query(Integer.class).single();
        assertThat(conflicts).isGreaterThanOrEqualTo(1);
    }

    @Test
    void changedLinkedRecordRecomputesGoldenAndCanReturnToEarlierPayload() {
        String suffix = UUID.randomUUID().toString();
        var original = record("revision-" + suffix, "Maya Patel", "maya." + suffix + "@example.com",
                "+14155550222", "21 Oak Street", "94107");
        var duplicate = record("revision-bill-" + suffix, "Maya Patel", original.email(),
                original.phone(), "21 Oak St", "94107");
        ingestion.ingest("CRM", new IngestBatchRequest(List.of(original)));
        ingestion.ingest("Billing", new IngestBatchRequest(List.of(duplicate)));
        executeRun();
        UUID sourceId = source("CRM", original.sourceRecordId());
        UUID goldenId = goldens.membership(sourceId);
        long beforeVersion = ((Number) goldens.get(goldenId).get("version")).longValue();

        var changed = new IngestRecordRequest(original.sourceRecordId(), original.fullName(), original.email(),
                "+14155550333", original.address(), original.postalCode(), Instant.parse("2026-02-01T00:00:00Z"));
        ingestion.ingest("CRM", new IngestBatchRequest(List.of(changed)));
        var changedGolden = goldens.get(goldenId);
        assertThat(((Number) changedGolden.get("version")).longValue()).isGreaterThan(beforeVersion);
        assertThat(changedGolden.get("phone")).isEqualTo("+14155550333");

        var backToOriginal = ingestion.ingest("CRM", new IngestBatchRequest(List.of(original)));
        assertThat(backToOriginal.created()).isEqualTo(1);
        assertThat(backToOriginal.items().getFirst().revisionNumber()).isEqualTo(3);
        assertThat(goldens.get(goldenId).get("phone")).isEqualTo(original.phone());
    }

    @Test
    void splitCreatesTwoActiveGoldensAndKeepsHistoryVisibleFromBoth() {
        String suffix = UUID.randomUUID().toString();
        var left = record("split-left-" + suffix, "Lena Clark", "lena." + suffix + "@example.com",
                "+14155550444", "8 Cedar Street", "94108");
        var right = record("split-right-" + suffix, "Lena Clark", left.email(), left.phone(),
                "8 Cedar St", "94108");
        ingestion.ingest("CRM", new IngestBatchRequest(List.of(left)));
        ingestion.ingest("Billing", new IngestBatchRequest(List.of(right)));
        executeRun();
        UUID leftSource = source("CRM", left.sourceRecordId());
        UUID rightSource = source("Billing", right.sourceRecordId());
        UUID originalGolden = goldens.membership(leftSource);
        long version = ((Number) goldens.get(originalGolden).get("version")).longValue();

        var result = goldens.split(originalGolden, new SplitRequest(List.of(rightSource),
                "Integration test correction", version), "reviewer");
        UUID newGolden = (UUID) result.get("newGoldenId");
        assertThat(goldens.membership(leftSource)).isEqualTo(originalGolden);
        assertThat(goldens.membership(rightSource)).isEqualTo(newGolden);
        assertThat(goldens.get(originalGolden).get("history").toString()).contains("SPLIT");
        assertThat(goldens.get(newGolden).get("history").toString()).contains("SPLIT");
    }

    @Test
    void overlappingRunsClaimEachRevisionOnlyOnce() throws Exception {
        String suffix = UUID.randomUUID().toString();
        ingestion.ingest("CRM", new IngestBatchRequest(List.of(
                record("claim-a-" + suffix, "Aria King", "claim.a." + suffix + "@example.com",
                        "+14155550551", "901 Lake Street", "95001"),
                record("claim-b-" + suffix, "Noah Green", "claim.b." + suffix + "@example.com",
                        "+14155550552", "902 Hill Street", "95002"))));
        int available = jdbc.sql("""
                SELECT count(*) FROM source_records sr WHERE NOT EXISTS (
                  SELECT 1 FROM match_run_items mri JOIN rule_sets rs ON rs.id = mri.ruleset_id
                  WHERE mri.source_revision_id = sr.current_revision_id AND rs.active)
                """).query(Integer.class).single();
        UUID first = pipeline.createRun();
        UUID second = pipeline.createRun();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> { start.await(); pipeline.claim(first); return null; });
            var two = executor.submit(() -> { start.await(); pipeline.claim(second); return null; });
            start.countDown();
            one.get(10, TimeUnit.SECONDS); two.get(10, TimeUnit.SECONDS);
        }
        int claimed = jdbc.sql("SELECT count(*) FROM match_run_items WHERE run_id IN (:runs)")
                .param("runs", List.of(first, second)).query(Integer.class).single();
        int overlap = jdbc.sql("""
                SELECT count(*) FROM match_run_items a JOIN match_run_items b
                  ON a.source_revision_id = b.source_revision_id AND a.run_id < b.run_id
                WHERE a.run_id IN (:runs) AND b.run_id IN (:runs)
                """).param("runs", List.of(first, second)).query(Integer.class).single();
        assertThat(claimed).isEqualTo(available);
        assertThat(overlap).isZero();
        finishClaimedRun(first); finishClaimedRun(second);
    }

    @Test
    void simultaneousReviewDecisionsCommitExactlyOnceAndReplayIdempotently() throws Exception {
        String suffix = UUID.randomUUID().toString();
        var left = record("review-left-" + suffix, "Avery Hall", "avery.left." + suffix + "@example.com",
                "+14155550661", "77 Market Street", "94103");
        var right = record("review-right-" + suffix, "Avery Hall", "avery.right." + suffix + "@example.com",
                "+14155550662", "77 Market St", "94103");
        ingestion.ingest("CRM", new IngestBatchRequest(List.of(left, right)));
        UUID run = executeRun();
        assertThat(reviews.list(null, null, null, 1).items()).isNotEmpty();
        UUID reviewId = jdbc.sql("""
                SELECT ri.id FROM review_items ri JOIN match_candidates mc ON mc.id = ri.candidate_id
                JOIN source_record_revisions lr ON lr.id = mc.left_revision_id
                JOIN source_record_revisions rr ON rr.id = mc.right_revision_id
                JOIN source_records ls ON ls.id = lr.source_record_id
                JOIN source_records rs ON rs.id = rr.source_record_id
                WHERE ri.run_id = :run AND ls.source_record_id IN (:keys) AND rs.source_record_id IN (:keys)
                """).param("run", run).param("keys", List.of(left.sourceRecordId(), right.sourceRecordId()))
                .query(UUID.class).single();
        String firstKey = "decision-" + UUID.randomUUID();
        String secondKey = "decision-" + UUID.randomUUID();
        var start = new CountDownLatch(1);
        List<String> outcomes;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> decideAfter(start, reviewId, firstKey));
            var two = executor.submit(() -> decideAfter(start, reviewId, secondKey));
            start.countDown();
            outcomes = List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        }
        assertThat(outcomes).filteredOn(value -> value.startsWith("OK:")).hasSize(1);
        assertThat(outcomes).filteredOn(value -> value.startsWith("CONFLICT:")).hasSize(1);
        int decisions = jdbc.sql("SELECT count(*) FROM review_decisions WHERE review_item_id = :id")
                .param("id", reviewId).query(Integer.class).single();
        assertThat(decisions).isEqualTo(1);

        String winningKey = outcomes.stream().filter(value -> value.startsWith("OK:"))
                .findFirst().orElseThrow().substring(3);
        var replay = reviews.decide(reviewId, new DecisionRequest("NO_MATCH", winningKey, 0L, "race test", null), "reviewer");
        assertThat(replay.get("decision")).isEqualTo("NO_MATCH");

        UUID latestDecision = jdbc.sql("SELECT id FROM review_decisions WHERE review_item_id = :id")
                .param("id", reviewId).query(UUID.class).single();
        var correction = reviews.decide(reviewId, new DecisionRequest("MATCH", "correction-" + UUID.randomUUID(),
                1L, "Corrected after additional verification", latestDecision), "reviewer");
        assertThat(correction.get("decision")).isEqualTo("MATCH");
        assertThat(jdbc.sql("SELECT count(*) FROM review_decisions WHERE review_item_id = :id")
                .param("id", reviewId).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void failureAtFinalAuditWriteRollsBackEntireMerge() {
        String suffix = UUID.randomUUID().toString();
        var left = record("rollback-left-" + suffix, "June Miller", "rollback.left." + suffix + "@example.com",
                "+14155550771", "19 Sunset Street", "94109");
        var right = record("rollback-right-" + suffix, "Omar Davis", "rollback.right." + suffix + "@example.com",
                "+14155550772", "20 Sunset Street", "94110");
        ingestion.ingest("CRM", new IngestBatchRequest(List.of(left, right)));
        UUID ruleset = jdbc.sql("SELECT id FROM rule_sets WHERE active").query(UUID.class).single();
        UUID leftSource = source("CRM", left.sourceRecordId());
        UUID rightSource = source("CRM", right.sourceRecordId());
        UUID leftGolden = goldens.ensureSingleton(leftSource, ruleset);
        UUID rightGolden = goldens.ensureSingleton(rightSource, ruleset);
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION force_reconciliation_event_failure() RETURNS trigger AS $$
                BEGIN
                  IF NEW.reason = 'FORCE_ROLLBACK_TEST' THEN RAISE EXCEPTION 'forced rollback'; END IF;
                  RETURN NEW;
                END; $$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("""
                CREATE TRIGGER force_reconciliation_event_failure_trigger
                BEFORE INSERT ON reconciliation_events FOR EACH ROW
                EXECUTE FUNCTION force_reconciliation_event_failure()
                """).update();
        try {
            assertThatThrownBy(() -> goldens.merge(leftSource, rightSource, ruleset, "test", "FORCE_ROLLBACK_TEST"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            jdbc.sql("DROP TRIGGER force_reconciliation_event_failure_trigger ON reconciliation_events").update();
            jdbc.sql("DROP FUNCTION force_reconciliation_event_failure()").update();
        }
        assertThat(goldens.membership(leftSource)).isEqualTo(leftGolden);
        assertThat(goldens.membership(rightSource)).isEqualTo(rightGolden);
        assertThat(goldens.membership(leftSource)).isNotEqualTo(goldens.membership(rightSource));
    }

    private UUID executeRun() {
        UUID run = pipeline.createRun();
        pipeline.claim(run); pipeline.normalize(run); pipeline.generateCandidates(run);
        pipeline.scoreCandidates(run); pipeline.resolve(run); pipeline.publishReviews(run); pipeline.finish(run);
        return run;
    }

    private void finishClaimedRun(UUID run) {
        pipeline.normalize(run); pipeline.generateCandidates(run); pipeline.scoreCandidates(run);
        pipeline.resolve(run); pipeline.publishReviews(run); pipeline.finish(run);
    }

    private String decideAfter(CountDownLatch start, UUID reviewId, String key) throws Exception {
        start.await();
        try {
            reviews.decide(reviewId, new DecisionRequest("NO_MATCH", key, 0L, "race test", null), "reviewer");
            return "OK:" + key;
        } catch (com.masterdata.reconciliation.api.ApiException conflict) {
            return "CONFLICT:" + conflict.code();
        }
    }

    private UUID source(String system, String key) {
        return jdbc.sql("SELECT id FROM source_records WHERE source_system = :system AND source_record_id = :key")
                .param("system", system).param("key", key).query(UUID.class).single();
    }

    private IngestRecordRequest record(String id, String name, String email, String phone, String address, String postal) {
        return new IngestRecordRequest(id, name, email, phone, address, postal, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
