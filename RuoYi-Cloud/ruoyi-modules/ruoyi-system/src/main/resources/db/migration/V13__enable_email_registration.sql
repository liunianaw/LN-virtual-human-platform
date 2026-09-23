-- Platform accounts require verified email; the deployment can still turn this switch off for incident response.
INSERT INTO sys_config (config_name,config_key,config_value,config_type,create_by,create_time,update_by,update_time,remark)
VALUES ('账号自助-是否开启用户注册功能','sys.account.registerUser','true','Y','migration',NOW(),'',NULL,'邮箱验证码注册开关')
ON DUPLICATE KEY UPDATE config_value='true',update_by='migration',update_time=NOW(),remark='邮箱验证码注册开关';
