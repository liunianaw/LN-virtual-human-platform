-- Run explicitly against the existing framework database. No DROP / account deletion.
-- Repeatable: disable deprecated menus and permissions; preserve role mappings for review.
START TRANSACTION;
CREATE TEMPORARY TABLE IF NOT EXISTS ln_trim_menu_ids (menu_id BIGINT PRIMARY KEY);
DELETE FROM ln_trim_menu_ids;
INSERT INTO ln_trim_menu_ids
SELECT menu_id FROM sys_menu
WHERE component IN ('system/dept/index', 'system/post/index', 'system/notice/index',
                    'tool/build/index', 'tool/gen/index')
   OR perms LIKE 'system:dept:%' OR perms LIKE 'system:post:%'
   OR perms LIKE 'system:notice:%' OR perms LIKE 'tool:build:%' OR perms LIKE 'tool:gen:%'
   OR perms IN ('monitor:sentinel:list', 'monitor:server:list')
   OR path IN ('http://ruoyi.vip', 'https://ruoyi.vip');
UPDATE sys_menu SET visible = '1', status = '1'
WHERE menu_id IN (SELECT menu_id FROM ln_trim_menu_ids);
DROP TEMPORARY TABLE ln_trim_menu_ids;
COMMIT;
-- Log out/in to refresh permissions. All monitor:job:* entries are untouched.
