ALTER TABLE p_generation_step
    ADD COLUMN stage_started_at datetime(3) NULL AFTER status,
    ADD COLUMN reserved_attempt_id bigint NULL AFTER attempt_no,
    ADD UNIQUE KEY uq_generation_step_reserved_attempt (reserved_attempt_id),
    ADD CONSTRAINT ck_generation_step_reserved_attempt CHECK (reserved_attempt_id IS NULL OR reserved_attempt_id > 0);

UPDATE p_generation_step
SET stage_started_at = updated_at
WHERE stage_started_at IS NULL;

ALTER TABLE p_generation_step
    MODIFY COLUMN stage_started_at datetime(3) NOT NULL COMMENT '当前阶段开始时间，UTC';
