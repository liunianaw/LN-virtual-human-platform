ALTER TABLE s_session_snapshot ADD COLUMN voice_binding json NULL;

CREATE TABLE s_voice_task (
  id bigint NOT NULL PRIMARY KEY,
  account_id bigint NOT NULL,
  purpose varchar(16) NOT NULL,
  source_key varchar(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  voice_version_id bigint NOT NULL,
  binding_snapshot json NOT NULL,
  policy_version varchar(16) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'QUEUED',
  revision bigint NOT NULL DEFAULT 1,
  winner_attempt_id bigint NULL,
  session_id bigint NULL,
  turn_id bigint NULL,
  operation_id bigint NULL,
  connection_epoch bigint NULL,
  generation bigint NULL,
  grant_id bigint NULL,
  owner varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  cancel_requested_at datetime(3) NULL,
  deadline_at datetime(3) NOT NULL,
  input_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  input_char_count int NOT NULL,
  error_code varchar(64) NULL,
  result_reference json NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at datetime(3) NULL,
  UNIQUE KEY uq_voice_source (account_id,purpose,source_key),
  UNIQUE KEY uq_voice_operation (operation_id),
  KEY idx_voice_task_recovery (status,deadline_at,id),
  KEY idx_voice_task_account (account_id,created_at,id),
  CONSTRAINT fk_voice_task_operation FOREIGN KEY (operation_id) REFERENCES s_operation(id),
  CONSTRAINT ck_voice_task_context CHECK ((purpose='BUSINESS' AND session_id IS NOT NULL AND turn_id IS NOT NULL
    AND operation_id IS NOT NULL AND connection_epoch IS NOT NULL AND generation IS NOT NULL AND grant_id IS NOT NULL)
    OR (purpose='AUDITION' AND session_id IS NULL AND turn_id IS NULL AND operation_id IS NULL
    AND connection_epoch IS NULL AND generation IS NULL AND grant_id IS NULL)),
  CONSTRAINT ck_voice_task_status CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED','UNKNOWN')),
  CONSTRAINT ck_voice_task_identity CHECK (id>0 AND account_id>0 AND voice_version_id>0 AND revision IN (1,2) AND input_char_count BETWEEN 1 AND 200)
) ENGINE=InnoDB;

CREATE TABLE s_voice_attempt (
  id bigint NOT NULL PRIMARY KEY,
  task_id bigint NOT NULL,
  attempt_no int NOT NULL,
  provider_type varchar(64) NOT NULL,
  service_id bigint NOT NULL,
  voice_version_id bigint NOT NULL,
  model_revision varchar(128) NOT NULL,
  capability_version varchar(128) NOT NULL,
  request_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  dispatch_token_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state varchar(16) NOT NULL DEFAULT 'CREATED',
  worker_instance_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  worker_boot_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lease_epoch bigint NOT NULL DEFAULT 1,
  lease_expires_at datetime(3) NOT NULL,
  query_count int NOT NULL DEFAULT 0,
  query_next_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  dispatched_at datetime(3) NULL,
  accepted_at datetime(3) NULL,
  finished_at datetime(3) NULL,
  provider_request_id varchar(128) NULL,
  error_code varchar(64) NULL,
  failure_stage varchar(32) NULL,
  cost_source varchar(24) NOT NULL DEFAULT 'UNKNOWN',
  result_summary json NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_voice_attempt (task_id,attempt_no),
  UNIQUE KEY uq_voice_attempt_owner (task_id,id),
  KEY idx_voice_attempt_recovery (state,lease_expires_at,id),
  KEY idx_voice_attempt_query (state,query_next_at,id),
  CONSTRAINT fk_voice_attempt_task FOREIGN KEY (task_id) REFERENCES s_voice_task(id),
  CONSTRAINT ck_voice_attempt_no CHECK (attempt_no IN (1,2)),
  CONSTRAINT ck_voice_attempt_query CHECK (query_count BETWEEN 0 AND 12),
  CONSTRAINT ck_voice_attempt_state CHECK (state IN ('CREATED','QUEUED','DISPATCHING','ACCEPTED','SUCCEEDED','FAILED','CANCELLED','UNKNOWN'))
) ENGINE=InnoDB;
ALTER TABLE s_voice_task ADD CONSTRAINT fk_voice_task_winner
  FOREIGN KEY (id,winner_attempt_id) REFERENCES s_voice_attempt(task_id,id);

CREATE TABLE s_voice_event (
  attempt_id bigint NOT NULL,
  event_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (attempt_id,event_id),
  CONSTRAINT fk_voice_event_attempt FOREIGN KEY (attempt_id) REFERENCES s_voice_attempt(id)
) ENGINE=InnoDB;
-- Business settlement remains exclusively in s_operation / TtsFinalizationWorker.
