-- DEV-03: current restriction and the exact version proven by a no-cost capability probe.
ALTER TABLE p_relay_service
  ADD COLUMN admin_disabled tinyint NOT NULL DEFAULT 0 COMMENT 'Administrator restriction; denies new Relay operations',
  ADD COLUMN last_test_version_id bigint NULL COMMENT 'Version confirmed by the latest capability probe',
  ADD CONSTRAINT ck_p_relay_service_admin_disabled CHECK (admin_disabled IN (0, 1)),
  ADD CONSTRAINT ck_p_relay_service_last_test_version_id CHECK (last_test_version_id > 0);

INSERT INTO sys_menu
  (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark)
VALUES
  (1140,'LLM/ASR Relay',1,10,'relay-services','developer/relay/index','','RelayServices',1,0,'C','0','0','platform:relay:read','link','migration',NOW(),'',NULL,'开发者后端 LLM/ASR 转接'),
  (1141,'Relay 配置',1140,1,'','','','',1,0,'F','0','0','platform:relay:write','#','migration',NOW(),'',NULL,'配置版本、授权、测试与停用'),
  (1142,'管理员禁用 Relay',0,1,'','','','',1,0,'F','1','0','platform:relay:admin-disable','#','migration',NOW(),'',NULL,'管理员独立限制')
ON DUPLICATE KEY UPDATE menu_id=menu_id;

INSERT INTO sys_role_menu (role_id,menu_id) VALUES
  (1,1140),(1,1141),(1,1142),(2,1140),(2,1141)
ON DUPLICATE KEY UPDATE role_id=role_id;
