-- One-time browser connection tickets.  The opaque ticket is never stored;
-- only its SHA-256 digest is persisted and it is consumed before a WSS epoch.
CREATE TABLE IF NOT EXISTS s_runtime_ticket (
  id bigint NOT NULL,
  created_at datetime(3) NOT NULL,
  updated_at datetime(3) NOT NULL,
  ticket_hash binary(32) NOT NULL,
  account_id bigint NOT NULL,
  application_id bigint NOT NULL,
  session_id bigint NOT NULL,
  config_version_id bigint NOT NULL,
  voice_version_id bigint NOT NULL,
  provider_kind varchar(24) NOT NULL,
  provider_voice_ref varchar(128) NOT NULL,
  relay_version_ref varchar(128) NULL,
  purpose varchar(16) NOT NULL,
  status varchar(16) NOT NULL,
  expires_at datetime(3) NOT NULL,
  consumed_at datetime(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_s_runtime_ticket_hash (ticket_hash),
  KEY idx_s_runtime_ticket_expiry (status, expires_at),
  CONSTRAINT ck_s_runtime_ticket_id CHECK (id > 0),
  CONSTRAINT ck_s_runtime_ticket_account CHECK (account_id > 0),
  CONSTRAINT ck_s_runtime_ticket_application CHECK (application_id > 0),
  CONSTRAINT ck_s_runtime_ticket_session CHECK (session_id > 0),
  CONSTRAINT ck_s_runtime_ticket_config CHECK (config_version_id > 0),
  CONSTRAINT ck_s_runtime_ticket_voice CHECK (voice_version_id > 0),
  CONSTRAINT ck_s_runtime_ticket_purpose CHECK (purpose IN ('CONNECT', 'REAUTHORIZE')),
  CONSTRAINT ck_s_runtime_ticket_status CHECK (status IN ('ACTIVE', 'CONSUMED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='浏览器运行时一次性连接票据';

ALTER TABLE s_temp_object
  ADD COLUMN media_id varchar(64) NULL COMMENT '运行时音频公开给所属S令牌的逻辑标识' AFTER id,
  ADD UNIQUE KEY uq_s_temp_object_media (media_id);
