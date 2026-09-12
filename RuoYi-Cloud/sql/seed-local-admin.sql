-- 本地开发初始管理员与若依系统权限种子。
-- 目标库：platform_db；仅新增缺失记录，不删除或覆盖已有数据。

USE platform_db;

-- 默认本地账号：admin / admin123（BCrypt 哈希）。上线前必须重置密码。
INSERT INTO sys_user
    (user_id, dept_id, user_name, nick_name, user_type, email, email_normalized,
     email_verified_at, auth_epoch, avatar_file_id, phonenumber, sex, avatar,
     password, status, del_flag, login_ip, login_date, pwd_update_date,
     create_by, create_time, update_by, update_time, remark)
VALUES
    (1, NULL, 'admin', '平台管理员', '00', 'admin@local.invalid', 'admin@local.invalid',
     NOW(3), 1, NULL, '', '2', '',
     '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2',
     '0', '0', '', NULL, NOW(), 'bootstrap', NOW(), '', NULL, '本地开发初始管理员')
ON DUPLICATE KEY UPDATE user_id = user_id;

INSERT INTO sys_role
    (role_id, role_name, role_key, role_sort, data_scope, menu_check_strictly,
     dept_check_strictly, status, del_flag, create_by, create_time, update_by, update_time, remark)
VALUES
    (1, '超级管理员', 'admin', 1, '1', 1, 1, '0', '0', 'bootstrap', NOW(), '', NULL, '本地开发超级管理员')
ON DUPLICATE KEY UPDATE role_id = role_id;

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
VALUES
    (1,    '系统管理', 0,   1, 'system',    NULL,                  '', '',          1, 0, 'M', '0', '0', '',                    'system',      'bootstrap', NOW(), '', NULL, '系统管理目录'),
    (100,  '用户管理', 1,   1, 'user',      'system/user/index',   '', '',          1, 0, 'C', '0', '0', 'system:user:list',    'user',        'bootstrap', NOW(), '', NULL, '用户管理'),
    (101,  '角色管理', 1,   2, 'role',      'system/role/index',   '', '',          1, 0, 'C', '0', '0', 'system:role:list',    'peoples',     'bootstrap', NOW(), '', NULL, '角色管理'),
    (102,  '菜单管理', 1,   3, 'menu',      'system/menu/index',   '', '',          1, 0, 'C', '0', '0', 'system:menu:list',    'tree-table',  'bootstrap', NOW(), '', NULL, '菜单管理'),
    (108,  '日志管理', 1,   4, 'log',       '',                    '', '',          1, 0, 'M', '0', '0', '',                    'log',         'bootstrap', NOW(), '', NULL, '日志管理目录'),
    (500,  '操作日志', 108, 1, 'operlog',   'system/operlog/index','', '',          1, 0, 'C', '0', '0', 'system:operlog:list', 'form',        'bootstrap', NOW(), '', NULL, '操作日志'),
    (501,  '登录日志', 108, 2, 'logininfor','system/logininfor/index','', '',        1, 0, 'C', '0', '0', 'system:logininfor:list','logininfor', 'bootstrap', NOW(), '', NULL, '登录日志'),
    (1000, '用户查询', 100, 1, '',          '',                    '', '',          1, 0, 'F', '0', '0', 'system:user:query',   '#',           'bootstrap', NOW(), '', NULL, ''),
    (1001, '用户新增', 100, 2, '',          '',                    '', '',          1, 0, 'F', '0', '0', 'system:user:add',     '#',           'bootstrap', NOW(), '', NULL, ''),
    (1002, '用户修改', 100, 3, '',          '',                    '', '',          1, 0, 'F', '0', '0', 'system:user:edit',    '#',           'bootstrap', NOW(), '', NULL, ''),
    (1003, '用户删除', 100, 4, '',          '',                    '', '',          1, 0, 'F', '0', '0', 'system:user:remove',  '#',           'bootstrap', NOW(), '', NULL, '')
ON DUPLICATE KEY UPDATE menu_id = menu_id;

INSERT INTO sys_user_role (user_id, role_id)
VALUES (1, 1)
ON DUPLICATE KEY UPDATE user_id = user_id;

INSERT INTO sys_role_menu (role_id, menu_id)
VALUES
    (1, 1), (1, 100), (1, 101), (1, 102), (1, 108), (1, 500), (1, 501),
    (1, 1000), (1, 1001), (1, 1002), (1, 1003)
ON DUPLICATE KEY UPDATE role_id = role_id;
