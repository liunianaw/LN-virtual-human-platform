-- M1 login acceptance roles. Account passwords are intentionally not versioned;
-- the local-only provisioner creates the test accounts from secure runtime input.
INSERT INTO sys_role
    (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly,
     dept_check_strictly, status, del_flag, create_by, create_time, update_by, update_time, remark)
VALUES
    (910001, 'M1 开发者只读', 'm1-developer-readonly', 910001, '1', 1, 1, '0', '0', 'bootstrap', NOW(), '', NULL,
     'M1 验收：仅可查看用户列表与查询详情，不能新增、修改或删除'),
    (910002, 'M1 开发者运维', 'm1-developer-operator', 910002, '1', 1, 1, '0', '0', 'bootstrap', NOW(), '', NULL,
     'M1 验收：仅可查看操作日志，不能访问用户管理接口')
ON DUPLICATE KEY UPDATE role_id = role_id;

-- Read-only developer: system/user page plus its list and detail-query permissions.
INSERT INTO sys_role_menu (role_id, menu_id)
VALUES
    (910001, 1), (910001, 100), (910001, 1000),
    (910002, 1), (910002, 108), (910002, 500)
ON DUPLICATE KEY UPDATE role_id = role_id;
