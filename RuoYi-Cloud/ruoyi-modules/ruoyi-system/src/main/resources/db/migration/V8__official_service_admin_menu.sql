INSERT INTO sys_menu
    (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark)
VALUES
    (1105,'官方服务',1,5,'official-services','official-service/index','','OfficialServices',1,0,'C','0','0','platform:service:read','server','bootstrap',NOW(),'',NULL,'官方图像与 TTS 服务配置'),
    (1106,'官方服务保存',1105,1,'','','',1,0,'F','0','0','platform:service:write','#','bootstrap',NOW(),'',NULL,'保存服务与替换凭证'),
    (1107,'官方服务检查',1105,2,'','','',1,0,'F','0','0','platform:service:check','#','bootstrap',NOW(),'',NULL,'本地配置检查')
ON DUPLICATE KEY UPDATE menu_id=menu_id;
INSERT INTO sys_role_menu (role_id,menu_id) VALUES (1,1105),(1,1106),(1,1107) ON DUPLICATE KEY UPDATE role_id=role_id;
