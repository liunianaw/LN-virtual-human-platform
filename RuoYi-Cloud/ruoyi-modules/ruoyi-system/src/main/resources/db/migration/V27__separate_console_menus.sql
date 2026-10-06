-- Keep RuoYi's menu/role model. Each identity has a separate production component.
INSERT INTO sys_menu
 (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,
  menu_type,visible,status,perms,icon,create_by,create_time,remark)
VALUES
 (1180,'我的工作台',0,2,'developer','',NULL,'DeveloperWorkspace',1,1,'M','0','0','','dashboard','migration',now(),'开发者独立主类目'),
 (1181,'我的角色制作',1180,1,'avatar','avatar/developer/index',NULL,'DeveloperAvatarProduction',1,1,'C','0','0','system:asset:list','peoples','migration',now(),'本人私有角色制作'),
 (1182,'角色生成',1181,1,'#','',NULL,'',1,1,'F','0','0','system:asset:add','#','migration',now(),'本人角色生成'),
 (1183,'角色编辑',1181,2,'#','',NULL,'',1,1,'F','0','0','system:asset:edit','#','migration',now(),'本人角色编辑'),
 (1184,'角色删除',1181,3,'#','',NULL,'',1,1,'F','0','0','system:asset:remove','#','migration',now(),'本人角色删除');

-- Preserve each existing non-admin role's precise grants when replacing its old entry.
INSERT INTO sys_role_menu (role_id,menu_id)
SELECT rm.role_id,rm.menu_id+81 FROM sys_role_menu rm
JOIN sys_role r ON r.role_id=rm.role_id
WHERE rm.menu_id BETWEEN 1100 AND 1103 AND r.role_key!='admin'
ON DUPLICATE KEY UPDATE role_id=values(role_id);

DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.role_id=rm.role_id
WHERE rm.menu_id BETWEEN 1100 AND 1103 AND r.role_key!='admin';

UPDATE sys_menu SET menu_name='官方角色制作',component='avatar/admin/index',
 route_name='AdminAvatarProduction',is_cache=1,remark='管理员独立官方角色制作页面'
WHERE menu_id=1100;

UPDATE sys_menu SET parent_id=1180,order_num=2 WHERE menu_id=1150;
UPDATE sys_menu SET parent_id=1180,order_num=3 WHERE menu_id=1120;
UPDATE sys_menu SET parent_id=1180,order_num=4 WHERE menu_id=1160;

INSERT INTO sys_role_menu (role_id,menu_id)
SELECT DISTINCT rm.role_id,1180 FROM sys_role_menu rm
JOIN sys_role r ON r.role_id=rm.role_id
WHERE rm.menu_id IN (1181,1150,1120,1160) AND r.role_key!='admin'
ON DUPLICATE KEY UPDATE role_id=values(role_id);

-- Preserve other grants; the existing frontend filter removes empty parent menus.
