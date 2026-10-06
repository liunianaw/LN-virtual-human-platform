-- Sidebar categories are real RuoYi menus, editable through Menu Management.
INSERT INTO sys_menu
 (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,
  menu_type,visible,status,perms,icon,create_by,create_time,remark)
VALUES
 (1190,'资源管理',0,1,'resources','',NULL,'ResourceManagement',1,1,'M','0','0','','component','migration',now(),'官方资源与服务目录，由若依菜单管理维护'),
 (1191,'调用管理',0,2,'calls','',NULL,'CallManagement',1,1,'M','0','0','','chart','migration',now(),'任务与调用目录，由若依菜单管理维护');

UPDATE sys_menu SET parent_id=1190,order_num=1 WHERE menu_id=1100;
UPDATE sys_menu SET parent_id=1190,order_num=2 WHERE menu_id=1110;
UPDATE sys_menu SET parent_id=1190,order_num=3 WHERE menu_id=1115;
UPDATE sys_menu SET parent_id=1190,order_num=4 WHERE menu_id=1105;
UPDATE sys_menu SET parent_id=1190,order_num=5 WHERE menu_id=1170;
UPDATE sys_menu SET parent_id=1191 WHERE menu_id=2001;
UPDATE sys_menu SET order_num=3 WHERE menu_id=1 AND order_num=1;

-- Parent grants follow existing child grants; no new page/button permissions are added.
INSERT INTO sys_role_menu (role_id,menu_id)
SELECT DISTINCT role_id,1190 FROM sys_role_menu WHERE menu_id IN (1100,1110,1115,1105,1170)
ON DUPLICATE KEY UPDATE role_id=values(role_id);
INSERT INTO sys_role_menu (role_id,menu_id)
SELECT DISTINCT role_id,1191 FROM sys_role_menu WHERE menu_id=2001
ON DUPLICATE KEY UPDATE role_id=values(role_id);
