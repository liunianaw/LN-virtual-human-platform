-- The signing key version is independent of the Application Secret epoch.
-- Keep old keys until all grants issued with them have expired.
ALTER TABLE s_session_grant
  ADD COLUMN signing_key_version varchar(16) NOT NULL DEFAULT '1' COMMENT 'HMAC signing key version; independent of issuer_key_epoch',
  ADD COLUMN runtime_binding json NULL COMMENT 'Fixed DEBUG Voice binding; never included in v2 token';

ALTER TABLE s_session
  ADD COLUMN create_request_hash binary(32) NULL COMMENT 'BUSINESS create parameters; optional credential included only as keyed digest';

ALTER TABLE s_api_idempotency
  ADD COLUMN result_count int NULL COMMENT 'Persisted revocation count for idempotent response reconstruction';
