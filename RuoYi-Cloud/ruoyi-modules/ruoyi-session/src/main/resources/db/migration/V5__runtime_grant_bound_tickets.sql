-- Ticket consumption always resolves the exact grant; old unbound tickets fail closed.
ALTER TABLE s_runtime_ticket
  ADD COLUMN grant_id bigint NULL,
  ADD COLUMN expected_connection_epoch bigint NULL,
  ADD KEY idx_s_runtime_ticket_grant (grant_id),
  ADD CONSTRAINT ck_s_runtime_ticket_expected_epoch CHECK (expected_connection_epoch IS NULL OR expected_connection_epoch > 0);
