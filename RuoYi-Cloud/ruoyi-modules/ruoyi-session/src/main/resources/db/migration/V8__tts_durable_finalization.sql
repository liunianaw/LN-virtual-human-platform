-- Phase one keeps the existing operation as the sole business segment fact.
ALTER TABLE s_operation
  ADD COLUMN tts_dispatch_state varchar(16) NOT NULL DEFAULT 'NONE',
  ADD COLUMN tts_owner varchar(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN tts_reservation_known boolean NOT NULL DEFAULT false,
  ADD COLUMN tts_deadline_at datetime(3) NULL,
  ADD COLUMN settlement_outcome varchar(8) NULL,
  ADD COLUMN settlement_status varchar(24) NOT NULL DEFAULT 'NONE',
  ADD COLUMN settlement_attempts int NOT NULL DEFAULT 0,
  ADD COLUMN settlement_next_at datetime(3) NULL,
  ADD COLUMN settlement_error varchar(64) NULL,
  ADD CONSTRAINT ck_s_operation_tts_dispatch CHECK (tts_dispatch_state IN ('NONE','PREPARED','DISPATCHING','SUCCEEDED','FAILED')),
  ADD CONSTRAINT ck_s_operation_settlement CHECK (settlement_status IN ('NONE','PENDING','DONE','REVIEW_REQUIRED')),
  ADD CONSTRAINT ck_s_operation_settlement_outcome CHECK (settlement_outcome IN ('SETTLE','RELEASE','REVIEW')),
  ADD INDEX idx_s_operation_settlement (settlement_status,settlement_next_at,id),
  ADD INDEX idx_s_operation_tts_recovery (tts_dispatch_state,tts_deadline_at,id);

-- Existing held reservations are evidence, never a reason to resubmit synthesis.
UPDATE s_operation SET tts_dispatch_state=CASE WHEN status='SUCCEEDED' THEN 'SUCCEEDED' ELSE 'DISPATCHING' END,
  settlement_outcome=CASE WHEN status='SUCCEEDED' THEN 'SETTLE' WHEN status='CANCELLED' AND error_code='TTS_NOT_SUBMITTED' THEN 'RELEASE' ELSE 'REVIEW' END,
  tts_reservation_known=true,settlement_status='PENDING',settlement_next_at=utc_timestamp(3)
WHERE operation_type='TTS' AND quota_reservation_id IS NOT NULL;
