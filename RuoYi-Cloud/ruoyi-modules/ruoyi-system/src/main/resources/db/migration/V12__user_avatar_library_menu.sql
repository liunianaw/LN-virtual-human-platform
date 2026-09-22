INSERT INTO sys_menu
    (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark)
VALUES
    (1123,'我的角色',1,9,'my-avatars','asset/mine/index','','MyAvatars',1,0,'C','0','0','system:asset:list','peoples','migration',NOW(),'',NULL,'个人角色资产目录、引用提示与安全删除'),
    (1124,'我的角色删除',1123,1,'','','', '',1,0,'F','0','0','system:asset:remove','#','migration',NOW(),'',NULL,'删除无有效引用的个人角色')
ON DUPLICATE KEY UPDATE menu_id=menu_id;
INSERT INTO sys_role_menu (role_id,menu_id) VALUES (1,1123),(1,1124),(2,1123),(2,1124)
ON DUPLICATE KEY UPDATE role_id=role_id;
