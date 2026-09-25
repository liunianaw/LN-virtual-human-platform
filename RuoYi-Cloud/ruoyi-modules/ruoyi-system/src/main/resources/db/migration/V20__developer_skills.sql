-- DEV-04: bound the number of calls before a Tool version can be published.
ALTER TABLE p_skill_version
  ADD COLUMN max_calls_per_turn int NULL COMMENT 'Maximum Tool calls in one turn',
  ADD CONSTRAINT ck_p_skill_version_max_calls_per_turn CHECK (max_calls_per_turn between 1 and 10);

INSERT INTO sys_menu
  (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark)
VALUES
  (1150,'Skills',1,11,'skills','developer/skill/index','','DeveloperSkills',1,0,'C','0','0','platform:skill:read','skill','migration',NOW(),'',NULL,'Prompt 与 HTTP Tool'),
  (1151,'Skill 配置',1150,1,'','','','',1,0,'F','0','0','platform:skill:write','#','migration',NOW(),'',NULL,'创建版本、停用与删除'),
  (1152,'官方 Skill 管理',0,1,'','','','',1,0,'F','1','0','platform:skill:official','#','migration',NOW(),'',NULL,'仅管理员创建官方 Skill')
ON DUPLICATE KEY UPDATE menu_id=menu_id;

INSERT INTO sys_role_menu (role_id,menu_id) VALUES
  (1,1150),(1,1151),(1,1152),(2,1150),(2,1151)
ON DUPLICATE KEY UPDATE role_id=role_id;
