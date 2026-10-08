-- Complete only the existing RuoYi System Management operation permissions.
INSERT INTO sys_menu
 (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,
  menu_type,visible,status,perms,icon,create_by,create_time,remark)
VALUES
 (1300,'用户导入',100,5,'','',NULL,'',1,0,'F','0','0','system:user:import','#','migration',now(),''),
 (1301,'用户导出',100,6,'','',NULL,'',1,0,'F','0','0','system:user:export','#','migration',now(),''),
 (1302,'重置密码',100,7,'','',NULL,'',1,0,'F','0','0','system:user:resetPwd','#','migration',now(),'重置按钮权限；接口同时校验用户修改权限'),
 (1310,'角色查询',101,1,'','',NULL,'',1,0,'F','0','0','system:role:query','#','migration',now(),''),
 (1311,'角色新增',101,2,'','',NULL,'',1,0,'F','0','0','system:role:add','#','migration',now(),''),
 (1312,'角色修改',101,3,'','',NULL,'',1,0,'F','0','0','system:role:edit','#','migration',now(),'修改、数据权限与用户授权共用既有标识'),
 (1313,'角色删除',101,4,'','',NULL,'',1,0,'F','0','0','system:role:remove','#','migration',now(),''),
 (1314,'角色导出',101,5,'','',NULL,'',1,0,'F','0','0','system:role:export','#','migration',now(),''),
 (1320,'菜单查询',102,1,'','',NULL,'',1,0,'F','0','0','system:menu:query','#','migration',now(),''),
 (1321,'菜单新增',102,2,'','',NULL,'',1,0,'F','0','0','system:menu:add','#','migration',now(),''),
 (1322,'菜单修改',102,3,'','',NULL,'',1,0,'F','0','0','system:menu:edit','#','migration',now(),'修改与保存排序共用既有标识'),
 (1323,'菜单删除',102,4,'','',NULL,'',1,0,'F','0','0','system:menu:remove','#','migration',now(),''),
 (1330,'操作日志查询',500,1,'','',NULL,'',1,0,'F','0','0','system:operlog:query','#','migration',now(),''),
 (1331,'操作日志删除',500,2,'','',NULL,'',1,0,'F','0','0','system:operlog:remove','#','migration',now(),'删除与清空共用既有标识'),
 (1332,'操作日志导出',500,3,'','',NULL,'',1,0,'F','0','0','system:operlog:export','#','migration',now(),''),
 (1340,'登录日志删除',501,1,'','',NULL,'',1,0,'F','0','0','system:logininfor:remove','#','migration',now(),'删除与清空共用既有标识'),
 (1341,'登录日志导出',501,2,'','',NULL,'',1,0,'F','0','0','system:logininfor:export','#','migration',now(),''),
 (1342,'账户解锁',501,3,'','',NULL,'',1,0,'F','0','0','system:logininfor:unlock','#','migration',now(),'解除登录失败锁定')
ON DUPLICATE KEY UPDATE menu_id=values(menu_id);

-- Preserve every existing role grant; only the administrator gets the new records.
INSERT INTO sys_role_menu (role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m
 ON m.menu_id IN (1300,1301,1302,1310,1311,1312,1313,1314,1320,1321,1322,1323,1330,1331,1332,1340,1341,1342)
WHERE r.role_key='admin'
ON DUPLICATE KEY UPDATE role_id=values(role_id);

-- This is a permission under Account Quota Governance, not a root-level page.
UPDATE sys_menu SET parent_id=1117,order_num=1 WHERE menu_id=1163;
