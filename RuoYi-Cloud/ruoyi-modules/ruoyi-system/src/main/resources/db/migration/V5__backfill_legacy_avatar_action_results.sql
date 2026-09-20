-- Preserve already paid-for and processed legacy action outputs when the
-- per-action candidate-result model is introduced.  The generation step is
-- used as the deterministic result id because the target table is new and a
-- step can produce at most one successful legacy output.
INSERT INTO p_avatar_action_result
    (id, created_at, account_id, avatar_version_id, action_code,
     step_id, attempt_id, atlas_file_id, manifest_file_id,
     frame_count, fps, loop_enabled, frame_layout, qa_report, content_hash)
SELECT s.id,
       s.updated_at,
       s.account_id,
       t.avatar_version_id,
       s.action_code,
       s.id,
       a.id,
       atlas.id,
       manifest.id,
       CAST(JSON_UNQUOTE(JSON_EXTRACT(s.result_metadata, '$.manifest.frameCount')) AS UNSIGNED),
       CAST(JSON_UNQUOTE(JSON_EXTRACT(s.result_metadata, '$.manifest.fps')) AS DECIMAL(6,2)),
       CASE WHEN JSON_EXTRACT(s.result_metadata, '$.manifest.loop') = TRUE THEN 1 ELSE 0 END,
       JSON_EXTRACT(s.result_metadata, '$.manifest'),
       JSON_OBJECT(
           'geometryOk', JSON_EXTRACT(s.result_metadata, '$.manifest.geometryOk'),
           'warnings', JSON_EXTRACT(s.result_metadata, '$.manifest.warnings'),
           'visualAccepted', FALSE,
           'legacyBackfill', TRUE
       ),
       UNHEX(JSON_UNQUOTE(JSON_EXTRACT(s.result_metadata, '$.manifest.atlas.sha256')))
FROM p_generation_step s
JOIN p_generation_task t
  ON t.id = s.task_id AND t.account_id = s.account_id
JOIN p_generation_attempt a
  ON a.step_id = s.id
 AND a.account_id = s.account_id
 AND a.attempt_no = s.attempt_no
 AND a.status = 'SUCCEEDED'
JOIN p_file atlas
  ON atlas.id = s.result_file_id
 AND atlas.account_id = s.account_id
 AND atlas.status = 'AVAILABLE'
JOIN p_file manifest
  ON manifest.account_id = atlas.account_id
 AND manifest.object_key = CONCAT(
       LEFT(atlas.object_key, CHAR_LENGTH(atlas.object_key) - CHAR_LENGTH('atlas.png')),
       'manifest.json'
   )
 AND manifest.purpose = 'MANIFEST'
 AND manifest.status = 'AVAILABLE'
WHERE s.status = 'SUCCEEDED'
  AND s.action_code IS NOT NULL
  AND JSON_EXTRACT(s.result_metadata, '$.manifest.frameCount') IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(s.result_metadata, '$.manifest.atlas.sha256')) REGEXP '^[0-9a-fA-F]{64}$'
  AND NOT EXISTS (
      SELECT 1
      FROM p_avatar_action_result existing
      WHERE existing.step_id = s.id OR existing.attempt_id = a.id
  );
