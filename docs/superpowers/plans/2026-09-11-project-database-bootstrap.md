# Project Database Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce and run a non-destructive MySQL 8 bootstrap for `platform_db` and `session_db`, containing the designed business schema and necessary RuoYi system/compatibility tables.

**Architecture:** A committed SQL initializer is the single executable schema source for this bootstrap. It creates databases only if absent, sets utf8mb4/InnoDB defaults, creates the curated RuoYi tables and all schema-draft business tables using `CREATE TABLE IF NOT EXISTS`, and adds no sample users or upstream destructive statements. The controller runs it once against the user-authorized local MySQL service and verifies actual structure.

**Tech Stack:** MySQL 8.0, UTF-8 SQL, the existing RuoYi SQL reference, `docs/database/schema-draft.json`, PowerShell mysql client.

**Spec:** `docs/superpowers/specs/2026-09-11-cos-and-database-bootstrap-design.md`

## Global Constraints

- Create exactly `platform_db` and `session_db`, both with InnoDB and utf8mb4 defaults; do not use or create `ry-cloud`.
- Create all 35 platform and 13 session tables specified by `docs/database/schema-draft.json`; retain every documented field, type, nullable/default setting, CHECK, unique key, index and same-database foreign-key constraint.
- Put the required RuoYi system and compatibility tables in `platform_db`, preserving source-compatible identifiers while adding no demo accounts, organization data, menus, sample passwords, Seata, Quartz JDBC, generator, or announcement tables.
- The initializer must not contain `DROP DATABASE`, `DROP TABLE`, `TRUNCATE`, `DELETE`, `REPLACE`, credential literals, or production seed users.
- Do not claim service/Flyway integration: this task verifies schema initialization only and documents that module-level Flyway wiring remains future M1 work.

---

### Task 1: Generate and validate the complete bootstrap SQL

**Files:**
- Create: `RuoYi-Cloud/sql/init-platform-and-session.sql`
- Modify: `RuoYi-Cloud/sql/README.md`
- Modify: `docs/handoff/tasks/M1-DB-001.md`

**Interfaces:**
- Consumes: `docs/database/schema-draft.json` table metadata and `RuoYi-Cloud/sql/ry_20260417.sql` only as a system-table field reference.
- Produces: a UTF-8 SQL file executable by `mysql --default-character-set=utf8mb4 -u root -p < RuoYi-Cloud/sql/init-platform-and-session.sql`.

- [ ] **Step 1: Build the database prologue**

Start the SQL file with:

```sql
CREATE DATABASE IF NOT EXISTS `platform_db`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS `session_db`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

Never issue a destructive statement.

- [ ] **Step 2: Create curated platform system tables**

Extract only `CREATE TABLE` definitions needed for the documented RuoYi core and compatibility set from `ry_20260417.sql`, convert each to `CREATE TABLE IF NOT EXISTS` under `USE platform_db;`, and remove all source INSERT/DELETE/DROP statements. Include `sys_user`, `sys_role`, `sys_menu`, `sys_user_role`, `sys_role_menu`, `sys_dict_type`, `sys_dict_data`, `sys_config`, `sys_oper_log`, `sys_logininfor`, plus the compatibility tables required by current RuoYi mappers (`sys_dept`, `sys_post`, `sys_role_dept`, `sys_user_post`) and `sys_job`, `sys_job_log`.

- [ ] **Step 3: Materialize all business tables from the schema draft**

Under `USE platform_db;`, create all 35 objects whose JSON `db` value is `platform_db`. Under `USE session_db;`, create all 13 objects whose JSON `db` value is `session_db`. Preserve definitions mechanically from the JSON: every field must have its declared MySQL type, `NOT NULL` rule, default, comments, primary key, named unique/index keys, CHECK and only legal same-database foreign keys. Order creates so referenced tables precede local foreign keys; use named `ALTER TABLE ... ADD CONSTRAINT` clauses only if an unavoidable reference cycle requires it.

- [ ] **Step 4: Add static schema validation to the SQL README**

Document the exact command:

```powershell
$env:MYSQL_PWD = '<your-local-mysql-password>'
& 'D:\Mysql\Mysql8\bin\mysql.exe' --protocol=tcp --host=127.0.0.1 --port=3306 -u root --default-character-set=utf8mb4 < .\sql\init-platform-and-session.sql
Remove-Item Env:MYSQL_PWD
```

Also document the repeat-run behavior and the query expected to report `platform_db` with 51 tables (16 system/compatibility plus 35 business) and `session_db` with 13 business tables.

- [ ] **Step 5: Run offline and syntax validation without touching MySQL**

Run:

```powershell
rg -n -i 'DROP[[:space:]]+(DATABASE|TABLE)|TRUNCATE|DELETE[[:space:]]+FROM|REPLACE[[:space:]]+INTO|password[[:space:]]*=' RuoYi-Cloud/sql/init-platform-and-session.sql
$draft = Get-Content -Raw docs/database/schema-draft.json | ConvertFrom-Json
$sql = Get-Content -Raw RuoYi-Cloud/sql/init-platform-and-session.sql
$draft | ForEach-Object { if ($sql -notmatch ('(?i)CREATE TABLE IF NOT EXISTS `?' + [regex]::Escape($_.table) + '`?')) { throw "Missing table: $($_.table)" } }
git diff --check
```

Expected: forbidden-statement search has no matches; all 48 JSON tables are found; no whitespace errors.

- [ ] **Step 6: Commit the initializer without executing it**

Stage only the initializer, SQL README, and handoff by exact path. Commit message:

```text
feat(database): [M1-DB-001] 新增项目双库初始化脚本
```

### Task 2: Execute and inspect the authorized local database bootstrap

**Files:**
- Modify: `docs/handoff/tasks/M1-DB-001.md`

**Interfaces:**
- Consumes: committed `RuoYi-Cloud/sql/init-platform-and-session.sql` and the user-authorized local MySQL root credential.
- Produces: two initialized local databases and recorded, redacted verification evidence.

- [ ] **Step 1: Confirm target schemas are absent or empty before first execution**

Run a read-only query for `platform_db` and `session_db`; if either contains tables, stop rather than overwrite it.

- [ ] **Step 2: Execute the initializer once**

Use the MySQL password only through the controller process environment, execute the committed SQL once, and immediately clear the process environment variable. Do not put the password in a script, command history, committed file, or handoff record.

- [ ] **Step 3: Verify structure and repeat-run safety**

Run these redacted, read-only checks after the first run and after a second run:

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

Expected: 51 tables in `platform_db`, 13 tables in `session_db`; second run exits successfully and preserves counts.

- [ ] **Step 4: Record outcomes and commit the handoff update**

Record only MySQL version, table counts, commands and result status. Do not record credentials, column values or COS credentials. Commit message:

```text
docs(handoff): [M1-DB-001] 记录本机双库初始化验收
```
