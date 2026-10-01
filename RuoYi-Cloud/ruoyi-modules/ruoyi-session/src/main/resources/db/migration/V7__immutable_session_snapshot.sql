-- Rollback requires a pre-switch database backup and the matching previous service builds.
CREATE TABLE s_migration_audit (
  migration_key varchar(96) NOT NULL,
  metric_key varchar(96) NOT NULL,
  metric_value bigint NOT NULL,
  recorded_at datetime(3) NOT NULL,
  PRIMARY KEY (migration_key,metric_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
INSERT INTO s_migration_audit VALUES
  ('V7_SESSION_SNAPSHOT','sessions_before',(select count(*) from s_session),utc_timestamp(3)),
  ('V7_SESSION_SNAPSHOT','active_sessions_before',(select count(*) from s_session where status='ACTIVE'),utc_timestamp(3)),
  ('V7_SESSION_SNAPSHOT','active_grants_before',(select count(*) from s_session_grant where status='ACTIVE'),utc_timestamp(3)),
  ('V7_SESSION_SNAPSHOT','messages_before',(select count(*) from s_message),utc_timestamp(3)),
  ('V7_SESSION_SNAPSHOT','running_operations_before',(select count(*) from s_operation where status='RUNNING'),utc_timestamp(3));

-- Developer-integration simplification is intentionally one-way. Existing
-- grants and tickets reference versioned Application/Relay configuration, so
-- they are revoked rather than translated into the new immutable snapshot.
CREATE TABLE s_session_snapshot (
  id bigint NOT NULL,
  created_at datetime(3) NOT NULL,
  account_id bigint NOT NULL,
  application_id bigint NOT NULL,
  application_revision bigint NOT NULL,
  legacy_config_ref bigint NULL,
  avatar_version_id bigint NULL,
  voice_version_id bigint NULL,
  system_prompt mediumtext NULL,
  developer_config json NOT NULL,
  internal_skills json NOT NULL,
  allowed_scopes json NOT NULL,
  provider_voice_ref varchar(128) NULL,
  official_service_id bigint NULL,
  official_service_revision bigint NULL,
  PRIMARY KEY (id),
  KEY idx_s_session_snapshot_owner (account_id, application_id, created_at),
  CONSTRAINT ck_s_session_snapshot_id CHECK (id > 0),
  CONSTRAINT ck_s_session_snapshot_account CHECK (account_id > 0),
  CONSTRAINT ck_s_session_snapshot_application CHECK (application_id > 0),
  CONSTRAINT ck_s_session_snapshot_revision CHECK (application_revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='Session创建时冻结的Application运行配置';

INSERT INTO s_session_snapshot
  (id,created_at,account_id,application_id,application_revision,legacy_config_ref,
   avatar_version_id,voice_version_id,system_prompt,developer_config,internal_skills,
   allowed_scopes,provider_voice_ref,official_service_id,official_service_revision)
SELECT id,created_at,account_id,application_id,1,app_config_id,
       NULL,NULL,NULL,JSON_OBJECT(),JSON_ARRAY(),
       JSON_ARRAY('session:read','avatar:read','speak:write','asr:write','context:capture','guidance:receive'),
       NULL,NULL,NULL
FROM s_session;

UPDATE s_session
SET status='DELETED', auth_epoch=auth_epoch+1, connection_epoch=connection_epoch+1,
    active_turn_id=NULL, deleted_at=COALESCE(deleted_at,UTC_TIMESTAMP(3)),
    updated_at=UTC_TIMESTAMP(3), revision=revision+1
WHERE status <> 'DELETED';

UPDATE s_operation SET status=if(status='QUEUED','CANCELLED','UNKNOWN'),
  error_code='INTEGRATION_PROTOCOL_RETIRED',finished_at=utc_timestamp(3),updated_at=utc_timestamp(3)
WHERE status in ('QUEUED','RUNNING');
UPDATE s_turn SET status='INTERRUPTED',cancel_reason='INTEGRATION_PROTOCOL_RETIRED',
  text_status=if(text_status='RUNNING','INTERRUPTED',text_status),
  audio_status=if(audio_status='RUNNING','UNKNOWN',audio_status),
  playback_status=if(playback_status in ('WAITING','PLAYING'),'STOPPED',playback_status),
  ended_at=coalesce(ended_at,utc_timestamp(3)),updated_at=utc_timestamp(3)
WHERE status='RUNNING';
UPDATE s_temp_object SET status='DELETE_PENDING',next_delete_at=utc_timestamp(3),updated_at=utc_timestamp(3)
WHERE status IN ('UPLOADING','ACTIVE');

UPDATE s_session_grant
SET status='REVOKED', revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)), updated_at=UTC_TIMESTAMP(3)
WHERE status='ACTIVE';

DELETE FROM s_session_grant WHERE grant_source='CONSOLE_DEBUG';
ALTER TABLE s_session_grant
  DROP CHECK ck_s_session_grant_issuer,
  DROP CHECK ck_s_session_grant_grant_source,
  DROP INDEX idx_s_session_grant_4,
  DROP COLUMN issuer_console_ref,
  DROP COLUMN runtime_binding,
  ADD CONSTRAINT ck_s_session_grant_source CHECK (grant_source='BUSINESS_KEY'),
  ADD CONSTRAINT ck_s_session_grant_issuer CHECK (issuer_key_id IS NOT NULL AND issuer_key_epoch IS NOT NULL);

UPDATE s_principal SET status='DISABLED',updated_at=UTC_TIMESTAMP(3) WHERE principal_type='DEBUG';

DELETE FROM s_runtime_ticket;

ALTER TABLE s_session
  ADD COLUMN session_snapshot_id bigint NULL AFTER principal_id;
UPDATE s_session SET session_snapshot_id=id;
ALTER TABLE s_session
  MODIFY COLUMN session_snapshot_id bigint NOT NULL COMMENT 's_session_snapshot.id，不可变运行配置',
  ADD UNIQUE KEY uq_s_session_snapshot (session_snapshot_id),
  ADD CONSTRAINT ck_s_session_snapshot_ref CHECK (session_snapshot_id > 0),
  DROP CHECK ck_s_session_app_config_id,
  DROP COLUMN app_config_id;

ALTER TABLE s_runtime_ticket
  ADD COLUMN session_snapshot_id bigint NULL AFTER session_id;
ALTER TABLE s_runtime_ticket
  MODIFY COLUMN session_snapshot_id bigint NOT NULL,
  ADD CONSTRAINT ck_s_runtime_ticket_snapshot CHECK (session_snapshot_id > 0),
  DROP CHECK ck_s_runtime_ticket_config,
  DROP COLUMN config_version_id,
  DROP COLUMN relay_version_ref;

-- The platform no longer stores developer LLM conversations. Historical text
-- is removed; redacted turn/operation facts remain for usage reconciliation.
DROP TABLE s_message;

CREATE TABLE s_tool_invocation (
  id bigint NOT NULL,
  created_at datetime(3) NOT NULL,
  updated_at datetime(3) NOT NULL,
  account_id bigint NOT NULL,
  session_id bigint NOT NULL,
  skill_id bigint NOT NULL,
  idempotency_key varchar(64) NOT NULL,
  request_hash binary(32) NOT NULL,
  status varchar(20) NOT NULL,
  http_status int NULL,
  result_json json NULL,
  error_code varchar(64) NULL,
  finished_at datetime(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_s_tool_invocation (session_id,skill_id,idempotency_key),
  KEY idx_s_tool_invocation_limit (session_id,skill_id,status,created_at),
  CONSTRAINT ck_s_tool_invocation_id CHECK (id > 0),
  CONSTRAINT ck_s_tool_invocation_account CHECK (account_id > 0),
  CONSTRAINT ck_s_tool_invocation_session CHECK (session_id > 0),
  CONSTRAINT ck_s_tool_invocation_skill CHECK (skill_id > 0),
  CONSTRAINT ck_s_tool_invocation_status CHECK (status IN ('PROCESSING','SUCCEEDED','FAILED','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='开发者后端按Session调用HTTP Tool的幂等与限次事实';
INSERT INTO s_migration_audit VALUES
  ('V7_SESSION_SNAPSHOT','sessions_after',(select count(*) from s_session),utc_timestamp(3)),
  ('V7_SESSION_SNAPSHOT','snapshots_after',(select count(*) from s_session_snapshot),utc_timestamp(3)),
  ('V7_SESSION_SNAPSHOT','active_legacy_sessions_after',(select count(*) from s_session where status='ACTIVE'),utc_timestamp(3)),
  ('V7_SESSION_SNAPSHOT','snapshot_orphans_after',(select count(*) from s_session s left join s_session_snapshot snap on snap.id=s.session_snapshot_id where snap.id is null),utc_timestamp(3)),
  ('V7_SESSION_SNAPSHOT','active_grants_after',(select count(*) from s_session_grant where status='ACTIVE'),utc_timestamp(3)),
  ('V7_SESSION_SNAPSHOT','tickets_after',(select count(*) from s_runtime_ticket),utc_timestamp(3));
