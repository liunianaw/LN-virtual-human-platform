-- Official voices are saved as immutable candidates.  A preview application is
-- system-owned and is never selectable through the normal application flow.
ALTER TABLE p_application
    ADD COLUMN purpose varchar(20) NOT NULL DEFAULT 'USER' COMMENT 'USER/VOICE_PREVIEW' AFTER account_id,
    ADD COLUMN preview_voice_version_id bigint NULL COMMENT 'VOICE_PREVIEW candidate voice version' AFTER purpose,
    ADD KEY idx_p_application_preview_voice (account_id, purpose, preview_voice_version_id),
    ADD CONSTRAINT ck_p_application_purpose CHECK (purpose IN ('USER', 'VOICE_PREVIEW'));

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
VALUES
    (1110, '官方声音', 1, 6, 'official-voices', 'voice/official/index', '', 'OfficialVoices',
     1, 0, 'C', '0', '0', 'platform:officialVoice:read', 'service', 'bootstrap', NOW(), '', NULL,
     '官方声音候选、试听和人工发布'),
    (1111, '官方声音保存', 1110, 1, '', '', '', '',
     1, 0, 'F', '0', '0', 'platform:officialVoice:write', '#', 'bootstrap', NOW(), '', NULL,
     '保存官方声音候选和新版本'),
    (1112, '官方声音发布', 1110, 2, '', '', '', '',
     1, 0, 'F', '0', '0', 'platform:officialVoice:write', '#', 'bootstrap', NOW(), '', NULL,
     '人工确认试听后发布官方声音')
ON DUPLICATE KEY UPDATE menu_id = menu_id;

INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1, 1110), (1, 1111), (1, 1112)
ON DUPLICATE KEY UPDATE role_id = role_id;
