-- Registered users may create and publish only their own private Avatar versions.
-- The service rejects official/public publication unless the current user is an administrator.
INSERT INTO sys_role_menu (role_id,menu_id) VALUES
    (2,1),(2,1100),(2,1101),(2,1102),(2,1103)
ON DUPLICATE KEY UPDATE role_id=role_id;
