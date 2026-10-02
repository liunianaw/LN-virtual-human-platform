-- A binary collation separates opaque keys; it never merges historical rows.
-- Existing unique keys already exclude duplicates under the old, broader equality.
ALTER TABLE p_point_reservation
  MODIFY business_type varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  MODIFY business_id varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  ADD COLUMN tts_request_key varchar(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN tts_application_id bigint NULL,
  ADD INDEX idx_p_point_tts_request (account_id,tts_request_key,id);
ALTER TABLE p_point_entry
  MODIFY event_key varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;

-- Preserve every existing amount/rate. Only link historical segments to their original turn.
UPDATE p_point_reservation SET tts_request_key=substring_index(business_id,':',1)
WHERE business_type='TTS_SEGMENT' AND business_id REGEXP '^[1-9][0-9]{0,18}:[0-9]{1,5}$';
