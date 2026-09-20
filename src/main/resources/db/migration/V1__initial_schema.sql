CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE rule_sets (
    id UUID PRIMARY KEY,
    version VARCHAR(64) NOT NULL UNIQUE,
    normalization_version INTEGER NOT NULL CHECK (normalization_version > 0),
    config JSONB NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_one_active_ruleset ON rule_sets (active) WHERE active;

CREATE TABLE source_records (
    id UUID PRIMARY KEY,
    source_system VARCHAR(100) NOT NULL,
    source_record_id VARCHAR(200) NOT NULL,
    current_revision_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_system, source_record_id)
);

CREATE TABLE source_record_revisions (
    id UUID PRIMARY KEY,
    source_record_id UUID NOT NULL REFERENCES source_records(id),
    revision_number INTEGER NOT NULL CHECK (revision_number > 0),
    payload_hash CHAR(64) NOT NULL,
    raw_payload JSONB NOT NULL,
    source_updated_at TIMESTAMPTZ,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_record_id, payload_hash),
    UNIQUE (source_record_id, revision_number)
);
ALTER TABLE source_records ADD CONSTRAINT fk_source_current_revision
    FOREIGN KEY (current_revision_id) REFERENCES source_record_revisions(id);

CREATE TABLE normalized_records (
    id UUID PRIMARY KEY,
    source_revision_id UUID NOT NULL REFERENCES source_record_revisions(id),
    normalization_version INTEGER NOT NULL,
    full_name VARCHAR(500),
    first_name VARCHAR(200),
    surname VARCHAR(200),
    surname_soundex VARCHAR(20),
    email VARCHAR(500),
    phone_e164 VARCHAR(32),
    phone_last7 VARCHAR(7),
    address VARCHAR(1000),
    postal_code VARCHAR(40),
    street_number VARCHAR(40),
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_revision_id, normalization_version)
);

CREATE TABLE golden_records (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    status VARCHAR(30) NOT NULL CHECK (status IN ('ACTIVE', 'MERGED')),
    merged_into_id UUID REFERENCES golden_records(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((status = 'ACTIVE' AND merged_into_id IS NULL) OR
           (status = 'MERGED' AND merged_into_id IS NOT NULL))
);

CREATE TABLE golden_record_versions (
    id UUID PRIMARY KEY,
    golden_record_id UUID NOT NULL REFERENCES golden_records(id),
    version BIGINT NOT NULL CHECK (version > 0),
    full_name TEXT,
    email TEXT,
    phone TEXT,
    address TEXT,
    normalized_values JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (golden_record_id, version)
);

CREATE TABLE golden_memberships (
    source_record_id UUID PRIMARY KEY REFERENCES source_records(id),
    golden_record_id UUID NOT NULL REFERENCES golden_records(id),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_membership_golden ON golden_memberships(golden_record_id);

CREATE TABLE golden_field_provenance (
    id UUID PRIMARY KEY,
    golden_record_version_id UUID NOT NULL REFERENCES golden_record_versions(id),
    field_name VARCHAR(40) NOT NULL,
    source_revision_id UUID NOT NULL REFERENCES source_record_revisions(id),
    selection_rule TEXT NOT NULL,
    raw_value TEXT NOT NULL,
    normalized_value TEXT,
    UNIQUE (golden_record_version_id, field_name)
);

CREATE TABLE match_runs (
    id UUID PRIMARY KEY,
    ruleset_id UUID NOT NULL REFERENCES rule_sets(id),
    status VARCHAR(30) NOT NULL CHECK (status IN ('STARTING','RUNNING','COMPLETED','COMPLETED_WITH_ERRORS','FAILED')),
    stage VARCHAR(50),
    claimed_count INTEGER NOT NULL DEFAULT 0,
    candidate_count INTEGER NOT NULL DEFAULT 0,
    auto_match_count INTEGER NOT NULL DEFAULT 0,
    review_count INTEGER NOT NULL DEFAULT 0,
    no_match_count INTEGER NOT NULL DEFAULT 0,
    insufficient_count INTEGER NOT NULL DEFAULT 0,
    oversized_blocks INTEGER NOT NULL DEFAULT 0,
    apply_failures INTEGER NOT NULL DEFAULT 0,
    failure_summary TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE match_run_items (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES match_runs(id),
    source_revision_id UUID NOT NULL REFERENCES source_record_revisions(id),
    ruleset_id UUID NOT NULL REFERENCES rule_sets(id),
    state VARCHAR(30) NOT NULL CHECK (state IN ('CLAIMED','PROCESSING','COMPLETED','FAILED')),
    failure TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_revision_id, ruleset_id)
);
CREATE UNIQUE INDEX uq_active_revision_claim ON match_run_items(source_revision_id)
    WHERE state IN ('CLAIMED','PROCESSING');
CREATE INDEX idx_run_items_run ON match_run_items(run_id);

CREATE TABLE match_candidates (
    id UUID PRIMARY KEY,
    ruleset_id UUID NOT NULL REFERENCES rule_sets(id),
    first_run_id UUID NOT NULL REFERENCES match_runs(id),
    left_revision_id UUID NOT NULL REFERENCES source_record_revisions(id),
    right_revision_id UUID NOT NULL REFERENCES source_record_revisions(id),
    blocking_rules JSONB NOT NULL DEFAULT '[]'::jsonb,
    field_evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    score NUMERIC(8,6),
    automated_decision VARCHAR(40),
    decision_reason TEXT,
    apply_status VARCHAR(30) NOT NULL DEFAULT 'PENDING'
        CHECK (apply_status IN ('PENDING','APPLIED','CONFLICT','FAILED','NOT_APPLICABLE')),
    apply_failure TEXT,
    scored_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (left_revision_id < right_revision_id),
    CHECK (score IS NULL OR (score >= 0 AND score <= 1)),
    UNIQUE (ruleset_id, left_revision_id, right_revision_id)
);
CREATE INDEX idx_candidate_run ON match_candidates(first_run_id);

CREATE TABLE review_items (
    id UUID PRIMARY KEY,
    run_id UUID REFERENCES match_runs(id),
    candidate_id UUID REFERENCES match_candidates(id),
    type VARCHAR(40) NOT NULL CHECK (type IN ('CANDIDATE','CLUSTER_CONFLICT','RELINK_REVIEW')),
    status VARCHAR(30) NOT NULL CHECK (status IN ('PENDING','RESOLVED','STALE')),
    version BIGINT NOT NULL DEFAULT 0,
    context JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_pending_candidate_review ON review_items(candidate_id)
    WHERE status = 'PENDING' AND candidate_id IS NOT NULL;
CREATE INDEX idx_review_pending_cursor ON review_items(created_at, id) WHERE status = 'PENDING';

CREATE TABLE review_decisions (
    id UUID PRIMARY KEY,
    review_item_id UUID NOT NULL REFERENCES review_items(id),
    decision VARCHAR(20) NOT NULL CHECK (decision IN ('MATCH','NO_MATCH')),
    actor VARCHAR(200) NOT NULL,
    reason TEXT,
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    supersedes_id UUID REFERENCES review_decisions(id),
    result JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_decision_review_item ON review_decisions(review_item_id, created_at DESC);

CREATE TABLE reconciliation_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(30) NOT NULL CHECK (event_type IN ('MERGE','SPLIT','RECOMPUTE','RELINK')),
    golden_record_id UUID REFERENCES golden_records(id),
    actor VARCHAR(200) NOT NULL,
    reason TEXT,
    before_memberships JSONB NOT NULL,
    after_memberships JSONB NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_event_golden ON reconciliation_events(golden_record_id, created_at);

INSERT INTO rule_sets (id, version, normalization_version, config, active)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'v1',
    1,
    '{
      "weights":{"email":0.35,"phone":0.30,"name":0.20,"address":0.15},
      "thresholds":{"autoMatch":0.90,"review":0.72,"nameWithIdentifier":0.92,"nameWithBothIdentifiers":0.80},
      "maxBlockSize":500,
      "maxClusterSize":20,
      "sourcePriority":{
        "fullName":["CRM","Billing","Support"],
        "email":["CRM","Billing","Support"],
        "phone":["CRM","Billing","Support"],
        "address":["Billing","CRM","Support"]
      }
    }'::jsonb,
    TRUE
);
