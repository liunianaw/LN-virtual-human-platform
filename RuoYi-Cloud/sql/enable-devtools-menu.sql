-- Optional tooling environment only; backend -Pdevtools and frontend devtools build required.
UPDATE sys_menu SET visible = '0', status = '0'
WHERE component = 'tool/gen/index' OR perms LIKE 'tool:gen:%';
-- Assign tool:gen permissions to the intended developer role via role management.
