-- Avatar task, step and provider attempt facts remain after asset cleanup.
-- V17 cleared historical expiry timestamps; repeat here for environments with writes between migrations.
UPDATE p_generation_task SET expires_at = NULL WHERE expires_at IS NOT NULL;
ALTER TABLE p_generation_task
    MODIFY COLUMN expires_at datetime(3) NULL COMMENT 'Deprecated; business task facts are retained',
    ADD CONSTRAINT ck_p_generation_task_no_expiry CHECK (expires_at IS NULL);
