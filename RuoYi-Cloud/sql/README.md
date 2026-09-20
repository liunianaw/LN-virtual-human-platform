# SQL 使用边界

- init-platform-and-session.sql：本项目 MySQL 8.0.16+ 双库 bootstrap 的唯一可执行 schema 入口。首次初始化空库；包含 `platform_db` 的 16 张若依系统/兼容表和 35 张业务表，以及 `session_db` 的 13 张业务表，不含种子账号、角色、菜单、任务或任何凭据。
- ry_20260417.sql：若依原始业务建表与种子数据，只供空的隔离开发库初始化或编写正式迁移时参考，含 DROP TABLE，不能重放到已有库。
- ry_config_20260818.sql：若依原始 Nacos 库快照，包含已裁剪的配置，不能作为当前运行配置。当前模板以 ../config/nacos/ 为准；正式 Nacos 建库应使用匹配其版本的官方 schema。
- ry_seata_20210128.sql：来源参考，当前不使用、不导入。
- quartz.sql：保留的 Quartz JDBC schema。现有 job 仍使用上游默认内存调度器，任务定义/日志保存在 sys_job/sys_job_log；没有在本轮切换到 Quartz JDBC。将来切换时单独迁移和验收。
- trim-existing-framework.sql：对已有框架库显式执行的可重复菜单停用脚本，不删用户、角色、部门兼容表或任务数据。执行后重新登录。
- enable-devtools-menu.sql：开发工具环境按需启用生成器菜单。

## 项目双库初始化

业务字段、可空性及索引依据 [schema-draft.json](../../docs/database/schema-draft.json)，CHECK、关系与若依改造依据[数据库设计说明书](../../数据库设计说明书.md)第 2、4.2、6–8 节。若依定义从 `ry_20260417.sql` 仅复用建表结构；保留原字段标识及自增策略，覆盖 `sys_user` 的邮箱、验证时间、撤销版本、头像引用、密码非空和唯一约束，并补充角色、字典和配置唯一键。`p_file.purpose` 按第 4.2 节包含 `ACCOUNT_ICON`。

两个数据库默认 utf8mb4，每张表显式指定 InnoDB。业务表使用 `utf8mb4_0900_as_cs` 保护外部身份、事件和幂等标识的大小写；名称/描述列使用 `utf8mb4_0900_ai_ci`。两张对象表的 `object_key` 使用 `ascii_bin`，完整对象唯一键上限为 1408 字节，未截断索引。JSON 中的无名 UQ/IDX 按原顺序命名为 `uq_<table>_<n>` / `idx_<table>_<n>`；外键补齐所需索引。

脚本以 `CREATE DATABASE IF NOT EXISTS` 和 `CREATE TABLE IF NOT EXISTS` 重跑，所有 22 个外键都是同库 `RESTRICT`，父表先于子表创建。已有表只跳过，**不会检查或修复已有表的字段差异**，不改变现有记录，也没有 Flyway 历史。首次执行前必须确认两个目标库不存在或为空；非空的未知结构应先停止核对。DDL 可能部分成功，失败后保留现场查明原因，不清库重试。

在 `RuoYi-Cloud` 目录中，PowerShell 可执行命令如下；`-p` 由客户端交互提示密码，不把密码写进命令文本：

```powershell
& 'D:\Mysql\Mysql8\bin\mysql.exe' --protocol=tcp --host=127.0.0.1 --port=3306 -u root -p --default-character-set=utf8mb4 --execute="source sql/init-platform-and-session.sql"
if ($LASTEXITCODE -ne 0) { throw '数据库初始化失败，请保留现场并检查错误' }
```

实施计划原示例保留如下。**PowerShell 不支持输入重定向 `<`，这一段仅用于说明计划中的输入和清理动作，不可原样运行。** 应使用上面的原生 `source` 命令；若控制器已临时设置 `MYSQL_PWD`，可省去 `-p`，并在 `finally` 中清除该环境变量。

```powershell
$env:MYSQL_PWD = '<your-local-mysql-password>'
& 'D:\Mysql\Mysql8\bin\mysql.exe' --protocol=tcp --host=127.0.0.1 --port=3306 -u root --default-character-set=utf8mb4 < .\sql\init-platform-and-session.sql
Remove-Item Env:MYSQL_PWD
```

在支持 `<` 的 shell 中也可使用接口形式：

```sh
mysql --default-character-set=utf8mb4 -u root -p < RuoYi-Cloud/sql/init-platform-and-session.sql
```

首次执行和第二次执行后都查询表数与表清单，预期分别为 `platform_db = 51`（16 + 35）、`session_db = 13`：

```sql
SELECT table_schema, COUNT(*) AS table_count
FROM information_schema.tables
WHERE table_schema IN ('platform_db', 'session_db')
GROUP BY table_schema;

SELECT table_schema, table_name
FROM information_schema.tables
WHERE table_schema IN ('platform_db', 'session_db')
ORDER BY table_schema, table_name;
```

## 离线静态检查

在仓库根目录运行，不连接 MySQL。禁止语句搜索预期无输出（`rg` 的无匹配退出码为 1）；其余检查预期无异常、无空白错误。字段字典的表名属性实际为 `name`，不是计划示例中的 `table`；以下校验先验证属性存在，避免空属性导致假通过。

```powershell
rg -n -i 'DROP[[:space:]]+(DATABASE|TABLE)|TRUNCATE|DELETE[[:space:]]+FROM|REPLACE[[:space:]]+INTO|password[[:space:]]*=' RuoYi-Cloud/sql/init-platform-and-session.sql
if ($LASTEXITCODE -ne 1) { throw '禁止语句检查失败或搜索执行异常' }
$draft = Get-Content -Raw -Encoding UTF8 docs/database/schema-draft.json | ConvertFrom-Json
$sql = Get-Content -Raw -Encoding UTF8 RuoYi-Cloud/sql/init-platform-and-session.sql
if ($draft.Count -ne 48) { throw '字段字典表数变化，需要核对设计' }
$draft | ForEach-Object {
    if ([string]::IsNullOrWhiteSpace($_.name)) { throw '字段字典缺少 name' }
    if ($sql -notmatch ('(?i)CREATE TABLE IF NOT EXISTS `' + [regex]::Escape($_.name) + '`\s*\(')) {
        throw "Missing table: $($_.name)"
    }
}
if ([regex]::Matches($sql, '(?i)CREATE TABLE IF NOT EXISTS').Count -ne 64) { throw '预期共 64 张表' }
git diff --check
```

Task 1 仅完成静态结构核对，未连接或执行 MySQL。真实初始化、二次执行和结构验收由实施计划 Task 2 单独记录；服务数据源配置、管理员初始化、模块级 Flyway 接线、升级迁移以及 JSON/账号归属/发布状态的业务校验仍属后续 M1 工作。本脚本存在不代表可登录、业务接口已实现或 COS 已联通。

## M1 迁移验收状态（2026-09-13）

当前运行的平台库由 `ruoyi-system` Flyway 管理，历史为基线 V0、结构与管理员种子 V1、M1 账号角色边界 V2；会话库由 `ruoyi-session` Flyway 管理，历史为基线 V0 和会话结构 V1。已在隔离空库验证首次迁移和重启幂等；已在隔离库验证修改已执行 V1 会报 Flyway checksum mismatch。

这些迁移路径不执行 `clean` 或 `repair`，也不应将 `init-platform-and-session.sql` 重放到已被 Flyway 管理的现有库。服务重启与完整本机验收证据见 [M1-RUN-001](../../docs/handoff/tasks/M1-RUN-001.md)。
