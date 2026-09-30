-- Only the platform administrator may assign account limits or grant platform units.
INSERT INTO sys_menu
  (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark)
VALUES
  (1164,'账号额度配置',1160,2,'','','','',1,0,'F','0','0','platform:quota:manage','#','migration',NOW(),'',NULL,'管理员配置账号并发限额及授予分类额度')
ON DUPLICATE KEY UPDATE menu_id=menu_id;

INSERT INTO sys_role_menu (role_id,menu_id) VALUES (1,1164)
ON DUPLICATE KEY UPDATE role_id=role_id;
