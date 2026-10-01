-- Developer integration simplification.
-- Rollback: restore the database backup taken before this migration and deploy the previous
-- application build together. The removed credentials are deliberately not recoverable.

-- Stop old System/Session/Webhook workers first. Administrator-owned developer test
-- resources must be explicitly transferred or disabled before running this migration.
CREATE TEMPORARY TABLE ln_simplification_guard (
  blocked bigint NOT NULL,
  CONSTRAINT ck_confirm_admin_developer_resource_disposition CHECK (blocked=0)
);
INSERT INTO ln_simplification_guard
SELECT (SELECT count(*) FROM p_application a WHERE a.purpose='USER'
  AND a.status NOT IN ('DELETED','DISABLED') AND (a.account_id=1 OR EXISTS (
    SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id
    WHERE ur.user_id=a.account_id AND r.role_key='admin')))
  + (SELECT count(*) FROM p_skill s WHERE s.visibility='PRIVATE'
  AND s.status NOT IN ('DELETED','DISABLED') AND (s.account_id=1 OR EXISTS (
    SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id
    WHERE ur.user_id=s.account_id AND r.role_key='admin')));
DROP TEMPORARY TABLE ln_simplification_guard;

CREATE TABLE p_migration_audit (
  migration_key varchar(96) NOT NULL,
  metric_key varchar(96) NOT NULL,
  metric_value bigint NOT NULL,
  recorded_at datetime(3) NOT NULL,
  PRIMARY KEY (migration_key,metric_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='Destructive migration pre/post counts';

INSERT INTO p_migration_audit VALUES
  ('V23_DEVELOPER_SIMPLIFICATION','management_keys_before',(select count(*) from p_access_key where key_type='MANAGEMENT'),utc_timestamp(3)),
  ('V23_DEVELOPER_SIMPLIFICATION','relay_services_before',(select count(*) from p_relay_service),utc_timestamp(3)),
  ('V23_DEVELOPER_SIMPLIFICATION','webhook_endpoints_before',(select count(*) from p_webhook_endpoint),utc_timestamp(3)),
  ('V23_DEVELOPER_SIMPLIFICATION','applications_before',(select count(*) from p_application where purpose='USER'),utc_timestamp(3)),
  ('V23_DEVELOPER_SIMPLIFICATION','app_configs_before',(select count(*) from p_app_config),utc_timestamp(3)),
  ('V23_DEVELOPER_SIMPLIFICATION','skill_versions_before',(select count(*) from p_skill_version),utc_timestamp(3)),
  ('V23_DEVELOPER_SIMPLIFICATION','webhook_deliveries_before',(select count(*) from p_webhook_delivery),utc_timestamp(3)),
  ('V23_DEVELOPER_SIMPLIFICATION','webhook_attempts_before',(select count(*) from p_webhook_attempt),utc_timestamp(3)),
  ('V23_DEVELOPER_SIMPLIFICATION','retired_secrets_before',(select count(*) from p_secret where purpose in ('RELAY_ACCESS','WEBHOOK_SIGN')),utc_timestamp(3)),
  ('V23_DEVELOPER_SIMPLIFICATION','skills_before',(select count(*) from p_skill),utc_timestamp(3));

-- One current Application configuration.
ALTER TABLE p_application
  ADD COLUMN avatar_id bigint NULL COMMENT 'Current logical Avatar',
  ADD COLUMN voice_id bigint NULL COMMENT 'Current logical official Voice',
  ADD COLUMN system_prompt mediumtext NULL COMMENT 'Current developer System Prompt',
  ADD KEY idx_p_application_avatar_id (avatar_id),
  ADD KEY idx_p_application_voice_id (voice_id);

UPDATE p_application a
JOIN p_app_config c ON c.id=a.current_config_id AND c.application_id=a.id
JOIN p_avatar_version av ON av.id=c.avatar_version_id
LEFT JOIN p_voice_version vv ON vv.id=c.voice_version_id
SET a.avatar_id=av.avatar_id,a.voice_id=vv.voice_id,a.system_prompt=c.system_prompt
WHERE a.purpose='USER';

CREATE TABLE p_application_skill (
  id bigint NOT NULL,
  created_at datetime(3) NOT NULL,
  updated_at datetime(3) NOT NULL,
  account_id bigint NOT NULL,
  application_id bigint NOT NULL,
  skill_id bigint NOT NULL,
  sort_order int NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uq_p_application_skill (application_id,skill_id),
  KEY idx_p_application_skill_account (account_id,application_id,sort_order),
  KEY idx_p_application_skill_skill (skill_id),
  CONSTRAINT fk_p_application_skill_application FOREIGN KEY (application_id)
    REFERENCES p_application(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_p_application_skill_skill FOREIGN KEY (skill_id)
    REFERENCES p_skill(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_p_application_skill_id CHECK (id>0),
  CONSTRAINT ck_p_application_skill_sort CHECK (sort_order>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='Current logical Skill bindings for Application';

INSERT INTO p_application_skill
  (id,created_at,updated_at,account_id,application_id,skill_id,sort_order)
SELECT uuid_short(),utc_timestamp(3),utc_timestamp(3),a.account_id,a.id,sv.skill_id,min(b.sort_order)
FROM p_application a
JOIN p_app_skill b ON b.app_config_id=a.current_config_id AND b.enabled=1
JOIN p_skill_version sv ON sv.id=b.skill_version_id
WHERE a.purpose='USER'
GROUP BY a.account_id,a.id,sv.skill_id;

-- One current Skill configuration.
ALTER TABLE p_skill
  ADD COLUMN skill_type varchar(16) NULL,
  ADD COLUMN tool_name varchar(64) NULL,
  ADD COLUMN instructions mediumtext NULL,
  ADD COLUMN context_requirements json NULL,
  ADD COLUMN tool_url varchar(2048) NULL,
  ADD COLUMN http_method varchar(8) NULL,
  ADD COLUMN input_schema json NULL,
  ADD COLUMN output_schema json NULL,
  ADD COLUMN tool_secret_id bigint NULL,
  ADD COLUMN requires_user_credential tinyint NOT NULL DEFAULT 0,
  ADD COLUMN identity_binding json NULL,
  ADD COLUMN frontend_fields json NULL,
  ADD COLUMN timeout_ms int NULL,
  ADD COLUMN max_result_bytes bigint NULL,
  ADD COLUMN max_calls_per_session int NULL,
  ADD COLUMN import_format varchar(32) NULL,
  ADD COLUMN content_hash binary(32) NULL,
  ADD KEY idx_p_skill_tool_name (tool_name),
  ADD CONSTRAINT ck_p_skill_current_type CHECK (skill_type IN ('PROMPT','HTTP_TOOL')),
  ADD CONSTRAINT ck_p_skill_current_method CHECK (http_method IN ('GET','POST')),
  ADD CONSTRAINT ck_p_skill_current_user_credential CHECK (requires_user_credential IN (0,1)),
  ADD CONSTRAINT ck_p_skill_current_calls CHECK (max_calls_per_session between 1 and 100);

UPDATE p_skill s
JOIN p_skill_version v ON v.id=s.current_version_id
SET s.skill_type=v.skill_type,s.tool_name=v.tool_name,s.instructions=v.instructions,
    s.context_requirements=v.context_requirements,s.tool_url=v.tool_url,s.http_method=v.http_method,
    s.input_schema=v.input_schema,s.output_schema=v.output_schema,s.tool_secret_id=v.tool_secret_id,
    s.requires_user_credential=v.requires_user_credential,s.identity_binding=v.identity_binding,
    s.frontend_fields=v.frontend_fields,s.timeout_ms=v.timeout_ms,s.max_result_bytes=v.max_result_bytes,
    s.max_calls_per_session=coalesce(v.max_calls_per_turn,10),s.import_format=v.import_format,
    s.content_hash=v.content_hash;

-- Current and Session references use logical resource IDs. Old Sessions are stopped by the
-- matching session_db migration, so version references are removed instead of silently rebound.
UPDATE p_resource_reference SET state='RELEASED',released_at=utc_timestamp(3),
  next_check_at=null,updated_at=utc_timestamp(3)
WHERE holder_type='SESSION' AND state IN ('RESERVED','CONFIRMED');
DELETE FROM p_resource_reference
WHERE resource_type IN ('APP_CONFIG','SKILL_VERSION','RELAY_VERSION');
ALTER TABLE p_resource_reference DROP CHECK ck_p_resource_reference_resource_type;
ALTER TABLE p_resource_reference ADD CONSTRAINT ck_p_resource_reference_resource_type
  CHECK (resource_type IN ('APPLICATION_SNAPSHOT','AVATAR_VERSION','VOICE_VERSION','SKILL'));

INSERT INTO p_resource_reference
  (id,created_at,updated_at,account_id,holder_type,holder_id,operation_id,resource_type,resource_id,state,confirmed_at)
SELECT uuid_short(),utc_timestamp(3),utc_timestamp(3),x.account_id,'APP_CURRENT',x.application_id,
  concat('migration-v23-',x.application_id),'SKILL',x.skill_id,'CONFIRMED',utc_timestamp(3)
FROM p_application_skill x JOIN p_application a ON a.id=x.application_id
WHERE a.status NOT IN ('DELETED','DELETING')
ON DUPLICATE KEY UPDATE state='CONFIRMED',released_at=null,updated_at=values(updated_at);

DROP TABLE p_app_skill;
ALTER TABLE p_application
  DROP CHECK ck_p_application_current_config_id,
  DROP COLUMN current_config_id,
  DROP COLUMN current_policy;
DROP TABLE p_app_config;

ALTER TABLE p_skill
  DROP CHECK ck_p_skill_current_version_id,
  DROP COLUMN current_version_id;
DROP TABLE p_skill_version;

-- Relay-backed Voice versions are not valid official TTS bindings. Disable affected Voice
-- records, remove those versions, and narrow the remaining version schema to OFFICIAL only.
UPDATE p_voice v JOIN p_voice_version vv ON vv.id=v.current_version_id
SET v.current_version_id=NULL,v.status='DISABLED',v.revision=v.revision+1,v.updated_at=utc_timestamp(3)
WHERE vv.service_type='RELAY';
DELETE FROM p_voice_version WHERE service_type='RELAY';
ALTER TABLE p_voice_version
  DROP FOREIGN KEY fk_p_voice_version_relay_version_id,
  DROP INDEX idx_p_voice_version_fk_relay_version_id,
  DROP CHECK ck_p_voice_version_relay_version_id,
  DROP CHECK ck_p_voice_version_service_reference,
  DROP CHECK ck_p_voice_version_service_type,
  DROP COLUMN relay_version_id,
  ADD CONSTRAINT ck_p_voice_version_service_type CHECK (service_type='OFFICIAL'),
  ADD CONSTRAINT ck_p_voice_version_service_reference CHECK (official_service_id IS NOT NULL);

DROP TABLE p_relay_grant;
DROP TABLE p_relay_version;
DROP TABLE p_relay_service;

-- Default official ASR joins the existing administrator-managed official service catalog.
ALTER TABLE p_official_service DROP CHECK ck_p_official_service_capability;
ALTER TABLE p_official_service ADD CONSTRAINT ck_p_official_service_capability
  CHECK (capability IN ('AVATAR_GENERATION','TTS','ASR'));

-- Retire authenticators before their API code disappears. Values are overwritten rather than
-- copied into a replacement model.
UPDATE p_access_key
SET scopes=json_array('sessions:create','sessions:read','sessions:grant','sessions:end','sessions:revoke','skills:invoke'),
    updated_at=utc_timestamp(3)
WHERE key_type='APPLICATION' AND status='ACTIVE';

UPDATE p_access_key_audit SET actor_type='CONSOLE',actor_key_id=NULL WHERE actor_type='MANAGEMENT';
ALTER TABLE p_access_key_audit DROP CHECK ck_p_access_key_audit_actor;
ALTER TABLE p_access_key_audit ADD CONSTRAINT ck_p_access_key_audit_actor CHECK (actor_type='CONSOLE');

DELETE FROM p_access_key WHERE key_type='MANAGEMENT';
ALTER TABLE p_access_key
  DROP CHECK ck_p_access_key_key_type,
  DROP CHECK ck_p_access_key_application,
  ADD CONSTRAINT ck_p_access_key_key_type CHECK (key_type='APPLICATION'),
  ADD CONSTRAINT ck_p_access_key_application CHECK (application_id IS NOT NULL);

DELETE FROM p_secret WHERE purpose IN ('RELAY_ACCESS','WEBHOOK_SIGN');
ALTER TABLE p_secret DROP CHECK ck_p_secret_purpose;
ALTER TABLE p_secret ADD CONSTRAINT ck_p_secret_purpose
  CHECK (purpose IN ('OFFICIAL_PROVIDER','TOOL_ACCESS'));

-- Webhook delivery is fully retired. Pre-migration counts above are the retained audit fact.
ALTER TABLE p_generation_task DROP CHECK ck_p_generation_task_webhook_endpoint_id;
ALTER TABLE p_generation_task DROP COLUMN webhook_endpoint_id;
DROP TABLE p_webhook_attempt;
DROP TABLE p_webhook_delivery;
DROP TABLE p_webhook_endpoint;

-- Exactly four developer menu groups; administrators get separate public-resource entries.
DELETE FROM sys_role_menu WHERE role_id=1 AND menu_id IN
  (1120,1121,1122,1123,1124,1130,1131,1132,1133,
   1140,1141,1142,1150,1151,1160,1161,1162);
DELETE FROM sys_role_menu WHERE role_id=2 AND menu_id IN
  (1105,1106,1107,1110,1111,1112,1115,1116,1122,1123,1124,1130,1131,1133,
   1140,1141,1142,1152,1161,1162,1163,1164);
DELETE FROM sys_role_menu WHERE menu_id IN (1122,1130,1131,1133,1140,1141,1142,1161,1162);
DELETE FROM sys_menu WHERE menu_id IN (1122,1131,1130,1133,1141,1140,1142,1162,1161);

UPDATE sys_menu SET menu_name='角色制作',remark='同一制作功能；管理员发布官方，开发者发布私有' WHERE menu_id=1100;
UPDATE sys_menu SET parent_id=1117 WHERE menu_id=1164;
INSERT INTO sys_menu
  (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,
   visible,status,perms,icon,create_by,create_time,update_by,update_time,remark)
SELECT 1117,'账号额度治理',1,9,'account-quotas','operations/account-quota','','AccountQuotaGovernance',1,0,
  'C','0','0','platform:operations:reconcile','chart','migration',now(),'',NULL,'管理员账号级额度治理'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=1117);
INSERT INTO sys_role_menu (role_id,menu_id) VALUES (1,1117),(1,1164)
ON DUPLICATE KEY UPDATE role_id=values(role_id);

UPDATE sys_menu SET menu_name='Application',order_num=7,
  remark='当前配置与 Application Secret；无发布版本和调试入口' WHERE menu_id=1120;
UPDATE sys_menu SET remark='保存一份当前 Application 配置' WHERE menu_id=1121;
UPDATE sys_menu SET order_num=6,remark='Prompt 与 HTTP Tool 当前配置' WHERE menu_id=1150;
UPDATE sys_menu SET remark='保存当前配置、状态与删除' WHERE menu_id=1151;
UPDATE sys_menu SET order_num=8 WHERE menu_id=1160;
UPDATE sys_menu SET parent_id=1170,remark='管理员公共 Skill 生命周期' WHERE menu_id=1152;

INSERT INTO sys_menu
  (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,
   menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark)
VALUES
  (1170,'公共 Skills',1,7,'public-skills','developer/skill/index','','PublicSkills',1,0,
   'C','0','0','platform:skill:official','skill','migration',now(),'',NULL,'仅管理员维护公共 Skills')
ON DUPLICATE KEY UPDATE menu_name=values(menu_name),component=values(component),perms=values(perms);

INSERT INTO sys_role_menu (role_id,menu_id) VALUES
  (1,1170),(1,1152),(1,1100),(1,1101),(1,1102),(1,1103),
  (2,1100),(2,1101),(2,1102),(2,1103),(2,1120),(2,1121),(2,1132),
  (2,1150),(2,1151),(2,1160)
ON DUPLICATE KEY UPDATE role_id=values(role_id);

INSERT INTO p_migration_audit VALUES
  ('V23_DEVELOPER_SIMPLIFICATION','applications_after',(select count(*) from p_application where purpose='USER'),utc_timestamp(3)),
  ('V23_DEVELOPER_SIMPLIFICATION','skills_after',(select count(*) from p_skill),utc_timestamp(3)),
  ('V23_DEVELOPER_SIMPLIFICATION','session_refs_active_after',(select count(*) from p_resource_reference where holder_type='SESSION' and state in ('RESERVED','CONFIRMED')),utc_timestamp(3)),
  ('V23_DEVELOPER_SIMPLIFICATION','management_keys_active_after',(select count(*) from p_access_key where key_type='MANAGEMENT' and status='ACTIVE'),utc_timestamp(3)),
  ('V23_DEVELOPER_SIMPLIFICATION','application_current_missing_after',(select count(*) from p_application where purpose='USER' and status!='DELETED' and (avatar_id is null or voice_id is null)),utc_timestamp(3)),
  ('V23_DEVELOPER_SIMPLIFICATION','skill_current_missing_after',(select count(*) from p_skill where status!='DELETED' and skill_type is null),utc_timestamp(3)),
  ('V23_DEVELOPER_SIMPLIFICATION','application_skill_orphans_after',(select count(*) from p_application_skill x left join p_application a on a.id=x.application_id left join p_skill s on s.id=x.skill_id where a.id is null or s.id is null),utc_timestamp(3));
