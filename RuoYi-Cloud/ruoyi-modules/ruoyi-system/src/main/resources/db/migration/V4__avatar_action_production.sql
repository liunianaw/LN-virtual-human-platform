-- Action results outlive generation-task retention. No cascading attempt FK.
ALTER TABLE p_avatar_version
    ADD COLUMN candidate_revision bigint NOT NULL DEFAULT 0,
    ADD COLUMN assembly_revision bigint NULL,
    ADD CONSTRAINT ck_avatar_candidate_revision CHECK (candidate_revision >= 0);

ALTER TABLE p_generation_task
    ADD COLUMN request_hash binary(32) NULL AFTER request_id;

INSERT INTO sys_role
    (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly,
     dept_check_strictly, status, del_flag, create_by, create_time, update_by, update_time, remark)
VALUES
    (2, '平台开发者', 'developer', 2, '1', 1, 1, '0', '0', 'bootstrap', NOW(), '', NULL,
     '个人开发者：管理本人私有资产并使用已发布公共资产')
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), status = '0', del_flag = '0';

INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (2, 1100), (2, 1101), (2, 1102), (2, 1103)
ON DUPLICATE KEY UPDATE role_id = role_id;

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.user_id, 2 FROM sys_user u
WHERE u.user_id <> 1 AND NOT EXISTS (SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.user_id)
ON DUPLICATE KEY UPDATE role_id = 2;

CREATE TABLE p_avatar_action_result (
    id bigint NOT NULL PRIMARY KEY,
    created_at datetime(3) NOT NULL,
    account_id bigint NOT NULL,
    avatar_version_id bigint NOT NULL,
    action_code varchar(24) NOT NULL,
    step_id bigint NULL,
    attempt_id bigint NULL,
    atlas_file_id bigint NOT NULL,
    manifest_file_id bigint NOT NULL,
    frame_count int NOT NULL,
    fps decimal(6,2) NOT NULL,
    loop_enabled tinyint NOT NULL,
    frame_layout json NOT NULL,
    qa_report json NOT NULL,
    content_hash binary(32) NOT NULL,
    UNIQUE KEY uq_avatar_result_attempt (attempt_id),
    KEY idx_avatar_result_owner (account_id, avatar_version_id, action_code),
    CONSTRAINT fk_avatar_result_version FOREIGN KEY (avatar_version_id) REFERENCES p_avatar_version(id),
    CONSTRAINT fk_avatar_result_atlas FOREIGN KEY (atlas_file_id) REFERENCES p_file(id),
    CONSTRAINT fk_avatar_result_manifest FOREIGN KEY (manifest_file_id) REFERENCES p_file(id),
    CONSTRAINT ck_avatar_result_action CHECK (action_code IN ('idle','speaking','listening','thinking','nod','shake_head','wave','happy')),
    CONSTRAINT ck_avatar_result_geometry CHECK (frame_count > 0 AND fps > 0 AND loop_enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE p_avatar_action_selection (
    avatar_version_id bigint NOT NULL,
    action_code varchar(24) NOT NULL,
    account_id bigint NOT NULL,
    revision bigint NOT NULL DEFAULT 0,
    selected_result_id bigint NULL,
    accepted_result_id bigint NULL,
    latest_attempt_id bigint NULL,
    selection_closed tinyint NOT NULL DEFAULT 0,
    accepted_by bigint NULL,
    accepted_at datetime(3) NULL,
    updated_at datetime(3) NOT NULL,
    PRIMARY KEY (avatar_version_id, action_code),
    KEY idx_avatar_selection_owner (account_id, avatar_version_id),
    CONSTRAINT fk_avatar_selection_version FOREIGN KEY (avatar_version_id) REFERENCES p_avatar_version(id),
    CONSTRAINT fk_avatar_selection_result FOREIGN KEY (selected_result_id) REFERENCES p_avatar_action_result(id),
    CONSTRAINT fk_avatar_selection_accepted FOREIGN KEY (accepted_result_id) REFERENCES p_avatar_action_result(id),
    CONSTRAINT ck_avatar_selection_action CHECK (action_code IN ('idle','speaking','listening','thinking','nod','shake_head','wave','happy')),
    CONSTRAINT ck_avatar_selection_revision CHECK (revision >= 0),
    CONSTRAINT ck_avatar_selection_closed CHECK (selection_closed IN (0,1)),
    CONSTRAINT ck_avatar_selection_accept CHECK (accepted_result_id IS NULL OR (selected_result_id IS NOT NULL AND accepted_result_id = selected_result_id))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE p_avatar_production_operation (
    account_id bigint NOT NULL,
    avatar_version_id bigint NOT NULL,
    action_code varchar(24) NOT NULL DEFAULT '',
    operation_code varchar(32) NOT NULL,
    request_id varchar(64) NOT NULL,
    request_hash binary(32) NOT NULL,
    response_json json NOT NULL,
    created_at datetime(3) NOT NULL,
    PRIMARY KEY (account_id, avatar_version_id, action_code, operation_code, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE p_avatar_version_operation (
    account_id bigint NOT NULL,
    avatar_id bigint NOT NULL,
    request_id varchar(64) NOT NULL,
    request_hash binary(32) NOT NULL,
    response_json json NOT NULL,
    created_at datetime(3) NOT NULL,
    PRIMARY KEY (account_id, avatar_id, request_id),
    CONSTRAINT fk_avatar_version_operation_avatar FOREIGN KEY (avatar_id) REFERENCES p_avatar(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

CREATE TABLE p_avatar_review (
    id bigint NOT NULL PRIMARY KEY,
    created_at datetime(3) NOT NULL,
    account_id bigint NOT NULL,
    avatar_id bigint NOT NULL,
    avatar_version_id bigint NOT NULL,
    visual_accepted tinyint NOT NULL,
    review_note varchar(500) NOT NULL,
    KEY idx_avatar_review_version (account_id, avatar_version_id, created_at),
    CONSTRAINT fk_avatar_review_version FOREIGN KEY (avatar_version_id) REFERENCES p_avatar_version(id),
    CONSTRAINT ck_avatar_review_accepted CHECK (visual_accepted IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;

-- Existing candidates get eight empty slots; no fabricated acceptance/result.
INSERT INTO p_avatar_action_selection (avatar_version_id, action_code, account_id, updated_at)
SELECT v.id, a.action_code, v.account_id, utc_timestamp(3)
FROM p_avatar_version v
CROSS JOIN (
    SELECT 'idle' AS action_code UNION ALL SELECT 'speaking' UNION ALL SELECT 'listening'
    UNION ALL SELECT 'thinking' UNION ALL SELECT 'nod' UNION ALL SELECT 'shake_head'
    UNION ALL SELECT 'wave' UNION ALL SELECT 'happy'
) a
WHERE v.status IN ('BUILDING','REVIEW');
