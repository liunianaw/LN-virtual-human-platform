-- Defaults apply only when the service creates a new verified self-registered account.
-- Seed missing settings only; preserve administrator values and all existing user balances.
INSERT INTO sys_config (config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '新用户注册赠送-并发额度','platform.registration.initialConcurrency','2','Y','migration',NOW(),
  '非负整数，最大10000；新用户的并发Session、制作任务和轮次分别按此值初始化。仅后续注册生效。'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='platform.registration.initialConcurrency');

INSERT INTO sys_config (config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '新用户注册赠送-存储容量MB','platform.registration.initialStorageMb','100','Y','migration',NOW(),
  '非负整数MB；1MB=1048576字节，同步初始化存储总容量及单文件上限。仅后续注册生效。'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='platform.registration.initialStorageMb');

INSERT INTO sys_config (config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '新用户注册赠送-积分','platform.registration.initialPoints','100','Y','migration',NOW(),
  '非负积分，最多两位小数；注册事务同时写入余额与系统赠送流水。仅后续注册生效。'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='platform.registration.initialPoints');
