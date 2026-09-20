-- M2 Avatar 制作后台入口：复用既有 avatar/index 页面和 system 资产 API。
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
VALUES
    (1100, 'Avatar 制作', 1, 5, 'avatar', 'avatar/index', '', 'AvatarWorkbench',
     1, 0, 'C', '0', '0', 'system:asset:list', 'picture', 'bootstrap', NOW(), '', NULL,
     '参考图制作、候选预览和人工发布'),
    (1101, 'Avatar 制作新增', 1100, 1, '', '', '', '',
     1, 0, 'F', '0', '0', 'system:asset:add', '#', 'bootstrap', NOW(), '', NULL,
     '上传参考图和提交制作任务'),
    (1102, 'Avatar 发布', 1100, 2, '', '', '', '',
     1, 0, 'F', '0', '0', 'system:asset:edit', '#', 'bootstrap', NOW(), '', NULL,
     '人工验收后发布候选版本'),
    (1103, 'Avatar 删除', 1100, 3, '', '', '', '',
     1, 0, 'F', '0', '0', 'system:asset:remove', '#', 'bootstrap', NOW(), '', NULL,
     '删除未被引用的 Avatar')
ON DUPLICATE KEY UPDATE menu_id = menu_id;

INSERT INTO sys_role_menu (role_id, menu_id)
VALUES (1, 1100), (1, 1101), (1, 1102), (1, 1103)
ON DUPLICATE KEY UPDATE role_id = role_id;
