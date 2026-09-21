-- USER applications are created through the console only; VOICE_PREVIEW remains isolated.
INSERT INTO sys_menu
    (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark)
VALUES
    (1120,'我的应用',1,8,'applications','application/index','','Applications',1,0,'C','0','0','platform:application:read','app','migration',NOW(),'',NULL,'应用创建、固定配置版本与调试入口'),
    (1121,'应用配置保存',1120,1,'','','', '',1,0,'F','0','0','platform:application:write','#','migration',NOW(),'',NULL,'创建、发布新配置版本和启停应用'),
    (1122,'应用调试',1120,2,'','','', '',1,0,'F','0','0','platform:application:debug','#','migration',NOW(),'',NULL,'从固定应用配置创建 DEBUG Session')
ON DUPLICATE KEY UPDATE menu_id=menu_id;

INSERT INTO sys_role_menu (role_id,menu_id) VALUES (1,1120),(1,1121),(1,1122),(2,1120),(2,1121),(2,1122)
ON DUPLICATE KEY UPDATE role_id=role_id;
