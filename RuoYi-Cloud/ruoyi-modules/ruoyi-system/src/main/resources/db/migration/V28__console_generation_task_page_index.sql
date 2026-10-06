-- Console tasks are filtered by account and sorted without a status restriction.
CREATE INDEX idx_generation_console_page ON p_generation_task (account_id,created_at,id);
