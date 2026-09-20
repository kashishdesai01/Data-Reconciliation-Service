ALTER TABLE source_record_revisions
    DROP CONSTRAINT source_record_revisions_source_record_id_payload_hash_key;
CREATE INDEX idx_revision_payload_hash
    ON source_record_revisions(source_record_id, payload_hash);

ALTER TABLE match_runs
    ADD COLUMN max_observed_block_size INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;
