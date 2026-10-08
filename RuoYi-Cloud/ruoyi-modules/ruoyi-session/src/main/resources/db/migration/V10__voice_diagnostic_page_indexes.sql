-- Admin diagnostics span accounts and support an optional status filter.
CREATE INDEX idx_voice_diagnostic_page ON s_voice_task (created_at,id);
CREATE INDEX idx_voice_diagnostic_status ON s_voice_task (status,created_at,id);
