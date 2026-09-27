-- DEV-08 keeps only a redacted CHAT turn summary. Earlier V1 remains immutable.
ALTER TABLE s_turn DROP CHECK ck_s_turn_history;
