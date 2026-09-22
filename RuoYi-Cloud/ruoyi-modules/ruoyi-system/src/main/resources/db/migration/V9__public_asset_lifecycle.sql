-- Public asset lifecycle keeps logical deletion separate from object removal.
ALTER TABLE p_avatar
    ADD COLUMN cleanup_status varchar(20) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PENDING/RUNNING/FAILED/COMPLETED' AFTER deleted_at,
    ADD COLUMN cleanup_attempt_count int NOT NULL DEFAULT 0 AFTER cleanup_status,
    ADD COLUMN next_cleanup_at datetime(3) NULL AFTER cleanup_attempt_count,
    ADD COLUMN cleanup_lease_epoch bigint NOT NULL DEFAULT 0 AFTER next_cleanup_at,
    ADD COLUMN cleanup_lease_owner varchar(128) NULL AFTER cleanup_lease_epoch,
    ADD COLUMN cleanup_lease_expires_at datetime(3) NULL AFTER cleanup_lease_owner,
    ADD COLUMN last_cleanup_error_code varchar(64) NULL AFTER cleanup_lease_expires_at,
    ADD KEY idx_p_avatar_cleanup (status, cleanup_status, next_cleanup_at);

ALTER TABLE p_voice
    ADD COLUMN cleanup_status varchar(20) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PENDING/RUNNING/FAILED/COMPLETED' AFTER deleted_at,
    ADD COLUMN cleanup_attempt_count int NOT NULL DEFAULT 0 AFTER cleanup_status,
    ADD COLUMN next_cleanup_at datetime(3) NULL AFTER cleanup_attempt_count,
    ADD COLUMN cleanup_lease_epoch bigint NOT NULL DEFAULT 0 AFTER next_cleanup_at,
    ADD COLUMN cleanup_lease_owner varchar(128) NULL AFTER cleanup_lease_epoch,
    ADD COLUMN cleanup_lease_expires_at datetime(3) NULL AFTER cleanup_lease_owner,
    ADD COLUMN last_cleanup_error_code varchar(64) NULL AFTER cleanup_lease_expires_at,
    ADD KEY idx_p_voice_cleanup (status, cleanup_status, next_cleanup_at);

INSERT INTO sys_menu
    (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark)
VALUES
    (1115,'公共资产生命周期',1,7,'public-assets','asset/public/index','','PublicAssets',1,0,'C','0','0','platform:asset:read','component','bootstrap',NOW(),'',NULL,'公共角色和声音的引用查看、下架、停用与删除'),
    (1116,'公共资产管理',1115,1,'','','','',1,0,'F','0','0','platform:asset:manage','#','bootstrap',NOW(),'',NULL,'下架、紧急停用和受保护删除')
ON DUPLICATE KEY UPDATE menu_id=menu_id;
INSERT INTO sys_role_menu (role_id,menu_id) VALUES (1,1115),(1,1116) ON DUPLICATE KEY UPDATE role_id=role_id;
