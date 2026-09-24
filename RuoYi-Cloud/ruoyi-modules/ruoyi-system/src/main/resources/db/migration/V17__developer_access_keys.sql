-- Fail migration if historical data violates the one-active-secret rule; inspect duplicates before deployment.
ALTER TABLE p_access_key
  ADD COLUMN active_application_id bigint GENERATED ALWAYS AS
    (CASE WHEN key_type = 'APPLICATION' AND status = 'ACTIVE' THEN application_id ELSE NULL END) STORED,
  ADD UNIQUE KEY uq_p_access_key_active_application (active_application_id);

ALTER TABLE p_application
  ADD COLUMN admin_disabled tinyint NOT NULL DEFAULT 0 COMMENT 'Only platform administrators may change this flag',
  ADD CONSTRAINT ck_p_application_admin_disabled CHECK (admin_disabled IN (0, 1));

-- Business facts survive session and asset cleanup; technical queues keep their own retention.
ALTER TABLE p_usage_daily MODIFY COLUMN expires_at datetime(3) NULL COMMENT 'No automatic deletion';
UPDATE p_usage_daily SET expires_at = NULL WHERE expires_at IS NOT NULL;
UPDATE p_call_record SET expires_at = NULL WHERE expires_at IS NOT NULL;
UPDATE p_generation_task SET expires_at = NULL WHERE expires_at IS NOT NULL;
UPDATE p_webhook_delivery SET expires_at = NULL WHERE expires_at IS NOT NULL;

CREATE TABLE p_access_key_audit (
  id bigint NOT NULL PRIMARY KEY,
  created_at datetime(3) NOT NULL,
  account_id bigint NOT NULL,
  key_id bigint NOT NULL,
  actor_type varchar(16) NOT NULL,
  actor_key_id bigint NULL,
  action varchar(16) NOT NULL,
  KEY idx_p_access_key_audit_account (account_id, created_at),
  CONSTRAINT ck_p_access_key_audit_actor CHECK (actor_type IN ('CONSOLE','MANAGEMENT')),
  CONSTRAINT ck_p_access_key_audit_action CHECK (action IN ('ACTIVE','DISABLED','DELETED'))
);

INSERT INTO sys_menu
  (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark)
VALUES
  (1130,'管理 Key',1,9,'access-keys','developer/access-key/index','','AccessKeys',1,0,'C','0','0','platform:access-key:read','key','migration',NOW(),'',NULL,'账号级管理 Key'),
  (1131,'管理 Key 写入',1130,1,'','','','',1,0,'F','0','0','platform:access-key:write','#','migration',NOW(),'',NULL,'创建、轮换、停用和删除'),
  (1132,'应用 Secret 管理',1120,3,'','','','',1,0,'F','0','0','platform:application:secret','#','migration',NOW(),'',NULL,'仅管理本人应用 Secret'),
  (1133,'管理员禁用应用',0,1,'','','','',1,0,'F','1','0','platform:application:admin-disable','#','migration',NOW(),'',NULL,'管理员专用，独立于开发者状态')
ON DUPLICATE KEY UPDATE menu_id=menu_id;

INSERT INTO sys_role_menu (role_id,menu_id) VALUES
  (1,1130),(1,1131),(1,1132),(1,1133),(2,1130),(2,1131),(2,1132)
ON DUPLICATE KEY UPDATE role_id=role_id;
