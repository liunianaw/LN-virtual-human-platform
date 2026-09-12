# M1 工程基础与服务入口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将已建立的双库、若依后台和目录骨架升级为可从空库以迁移启动、可验证服务边界的 M1 工程基础。

**Architecture:** `ruoyi-system` 是 platform-service，管理平台库；新增 `ruoyi-session` 管理会话库，两者各自使用独立 Flyway 历史和 Nacos 数据源。`ruoyi-media` 保持独立 Python API/Worker，不直写业务数据库；gateway/auth/Vue 沿用已有若依认证与权限链路。

**Tech Stack:** Java 17、Spring Boot 3.5、Spring Cloud Alibaba/Nacos、MySQL 8、Flyway、Redis、RabbitMQ、Vue 3/Vite、Python 3/FastAPI。

**Spec:** `docs/superpowers/specs/2026-09-09-phase-one-design.md`

## Global Constraints

- 仅保留现有一级目录；平台业务 Java 代码在 `RuoYi-Cloud/ruoyi-modules/`，不新建 platform-service 工程。
- platform-service 只写 `platform_db`，session-service 只写 `session_db`；`ruoyi-media` 不配置业务库直写。
- 迁移只追加、不修改已执行版本；生产路径禁止 Flyway `clean` 或 `repair`，负向 checksum 检查只使用隔离库。
- 密钥只由本地受忽略配置或部署环境注入；提交、日志、样例、交接不得包含真实值。
- M1 不接入付费生成、真实 TTS、声音克隆或完整 M2 业务表以外的新功能。
- 用户已确认现有前后端可正常启动、数据库已初始化；前端仅承担最小后端验证，不进行页面美化或额外交互开发。

---

## 已有基线（不重复实现）

- `RuoYi-Cloud/sql/init-platform-and-session.sql` 已在本机完成 51 张 `platform_db` 表、13 张 `session_db` 表的重复执行验证。
- `RuoYi-Cloud/sql/seed-local-admin.sql` 已可重复写入本地 `admin` 与最低系统权限；auth/system/gateway、Redis 和 Nacos 曾联通。
- `RuoYi-Cloud-Vue3` 已有若依登录、动态菜单、权限指令和构建脚本。M1 在此基础上做真实验收，不另造账号或权限体系。

### Task 1: 平台库可追溯迁移

**Files:**
- Create: `RuoYi-Cloud/ruoyi-modules/ruoyi-system/src/main/resources/db/migration/V1__platform_schema_and_admin_seed.sql`
- Modify: `RuoYi-Cloud/pom.xml`
- Modify: `RuoYi-Cloud/ruoyi-modules/ruoyi-system/pom.xml`
- Test: `RuoYi-Cloud/ruoyi-modules/ruoyi-system/src/main/resources/db/migration/Verify-PlatformMigration.ps1`

**Interfaces:**
- Consumes: `RuoYi-Cloud/sql/init-platform-and-session.sql` 中的 `platform_db` 结构，以及 `RuoYi-Cloud/sql/seed-local-admin.sql`。
- Produces: 对空 `platform_db` 执行的 `V1__platform_schema_and_admin_seed.sql`；Flyway history 中的 `version=1`、`success=1`。

- [ ] **Step 1: 从一次性脚本精确提取 platform 段并写入 V1**

```sql
-- V1 migration never creates database users or databases.
CREATE TABLE `sys_user` ( /* extracted platform baseline definition */ );
-- all 51 platform_db table definitions, indexes, foreign keys and required seed rows follow.
```

- [ ] **Step 2: 为 system 添加 MySQL Flyway runtime**

```xml
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-mysql</artifactId>
</dependency>
```

- [ ] **Step 3: 静态阻断危险迁移并核对表集合**

```powershell
$migration = Get-Content -Raw .\src\main\resources\db\migration\V1__platform_schema_and_admin_seed.sql
if ($migration -match '(?im)^\s*(CREATE\s+(DATABASE|USER)|DROP\s+DATABASE|GRANT\s+|.*FLYWAY.*\b(CLEAN|REPAIR)\b)') { throw 'migration contains a forbidden operation' }
```

- [ ] **Step 4: 在隔离空库执行启动与重启验证**

Run: `mvn -pl ruoyi-modules/ruoyi-system -am -DskipTests package` from `RuoYi-Cloud`.

Expected: system module packages; first isolated startup records V1 and 51 tables; restart applies no second version.

- [ ] **Step 5: Commit**

```bash
git add RuoYi-Cloud/pom.xml RuoYi-Cloud/ruoyi-modules/ruoyi-system
git commit -m "feat(database): [M1-PLAT-001] add platform Flyway baseline"
```

### Task 2: 会话服务和会话库边界

**Files:**
- Create: `RuoYi-Cloud/ruoyi-modules/ruoyi-session/pom.xml`
- Create: `RuoYi-Cloud/ruoyi-modules/ruoyi-session/src/main/java/com/ruoyi/session/RuoYiSessionApplication.java`
- Create: `RuoYi-Cloud/ruoyi-modules/ruoyi-session/src/main/resources/bootstrap.yml`
- Create: `RuoYi-Cloud/ruoyi-modules/ruoyi-session/src/main/resources/db/migration/V1__initialize_session_schema.sql`
- Create: `RuoYi-Cloud/config/nacos/ruoyi-session-dev.yml`
- Modify: `RuoYi-Cloud/ruoyi-modules/pom.xml`
- Test: `RuoYi-Cloud/ruoyi-modules/ruoyi-session/src/test/java/com/ruoyi/session/RuoYiSessionApplicationTest.java`

**Interfaces:**
- Consumes: `SESSION_DB_URL`、`SESSION_DB_USER`、`SESSION_DB_PASSWORD` 与通用 `NACOS_ADDR`/Redis 配置。
- Produces: 服务名 `ruoyi-session`、独立端口 `9202`（`ruoyi-gen` 仅在 `devtools` profile 下启用）、`/actuator/health`，以及只含 session 13 表的 Flyway V1。

- [ ] **Step 1: 写最小服务主类并验证 Spring 上下文**

```java
@SpringBootApplication
public class RuoYiSessionApplication {
    public static void main(String[] args) {
        SpringApplication.run(RuoYiSessionApplication.class, args);
    }
}
```

- [ ] **Step 2: 用独立环境变量配置会话数据源**

```yaml
spring:
  datasource:
    url: ${SESSION_DB_URL}
    username: ${SESSION_DB_USER}
    password: ${SESSION_DB_PASSWORD}
  flyway:
    locations: classpath:db/migration
```

- [ ] **Step 3: 迁入现有 session 13 表定义，静态检查其不含 platform 表、建库或凭据**

Run: `mvn -pl ruoyi-modules/ruoyi-session -am test` from `RuoYi-Cloud`.

Expected: reactor resolves the new module and its application-context test passes.

- [ ] **Step 4: 使用隔离 `session_db` 启动与重启验证**

Expected: first startup writes Flyway V1 and all 13 session tables; a restart has no DDL mutation.

- [ ] **Step 5: Commit**

```bash
git add RuoYi-Cloud/pom.xml RuoYi-Cloud/ruoyi-modules RuoYi-Cloud/config/nacos/ruoyi-session-dev.yml
git commit -m "feat(session): [M1-SESS-001] add session service baseline"
```

### Task 3: 独立媒体 API 和 Worker 健康入口

**Files:**
- Create: `ruoyi-media/pyproject.toml`
- Create: `ruoyi-media/src/ruoyi_media/api/app.py`
- Create: `ruoyi-media/src/ruoyi_media/worker/main.py`
- Create: `ruoyi-media/src/ruoyi_media/providers/__init__.py`
- Create: `ruoyi-media/src/ruoyi_media/media/__init__.py`
- Create: `ruoyi-media/tests/test_health.py`
- Create: `ruoyi-media/README.md`

**Interfaces:**
- Produces: API `GET /health` returns HTTP 200 and a stable ready payload; Worker writes a periodic ready/heartbeat event to stdout.
- Prohibits: business DB connection, real provider invocation, RabbitMQ consumer or secret loading in this task.

- [ ] **Step 1: 写 API 健康路由与测试**

```python
@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ready", "service": "ruoyi-media-api"}
```

- [ ] **Step 2: 写可中断 Worker ready/heartbeat 循环**

```python
while not shutdown_requested:
    logger.info("worker_ready", extra={"service": "ruoyi-media-worker"})
    stop_event.wait(30)
```

- [ ] **Step 3: 创建可复现依赖与本地验证**

Run: `python -m pytest` from `ruoyi-media`.

Expected: health test returns 200 with `status=ready`; Worker smoke command prints its ready marker without contacting external systems.

- [ ] **Step 4: Commit**

```bash
git add ruoyi-media
git commit -m "feat(media): [M1-MEDIA-001] add API and worker health skeleton"
```

### Task 4: 基础设施配置与服务注册

**Files:**
- Modify: `RuoYi-Cloud/config/nacos/ruoyi-system-dev.yml`
- Modify: `RuoYi-Cloud/config/nacos/application-dev.yml`
- Modify: `RuoYi-Cloud/docker/docker-compose.yml`
- Create: `RuoYi-Cloud/docker/.env.example`
- Create: `RuoYi-Cloud/bin/check-m1-services.ps1`
- Create: `RuoYi-Cloud/bin/publish-nacos-config.ps1`

**Interfaces:**
- Consumes: 启动顺序 MySQL/Redis → Nacos → RabbitMQ → system/session → auth/gateway → media API/Worker → Vue。
- Produces: system 仅使用 `PLATFORM_DB_*`，session 仅使用 `SESSION_DB_*`；RabbitMQ host 由环境变量提供；健康检查结果为逐服务 HTTP/进程级证据。

- [ ] **Step 1: 将 system 数据源从上游 `ry-cloud` 默认值改为 platform 专用环境变量**

```yaml
spring:
  datasource:
    url: ${PLATFORM_DB_URL}
    username: ${PLATFORM_DB_USER}
    password: ${PLATFORM_DB_PASSWORD}
```

- [ ] **Step 2: 为 Compose 增加 RabbitMQ、session、media API 和 media Worker，并使用 `.env` 替换所有敏感值**

```yaml
depends_on:
  rabbitmq:
    condition: service_healthy
```

- [ ] **Step 3: 以 HTTP health、Nacos 注册记录和 Worker 心跳验证依赖顺序**

Run: `powershell -ExecutionPolicy Bypass -File .\bin\check-m1-services.ps1` from `RuoYi-Cloud`.

Expected: 失败服务和未就绪依赖明确列出，所有目标就绪时以退出码 0 结束；不打印环境变量敏感值。

- [ ] **Step 4: 先演练再发布 Nacos 配置；令牌只由环境变量提供**

```powershell
.\bin\publish-nacos-config.ps1 -DataId ruoyi-session-dev.yml
$env:NACOS_ACCESS_TOKEN = '<provided outside the repository>'
.\bin\publish-nacos-config.ps1 -DataId ruoyi-session-dev.yml -Apply
Remove-Item Env:NACOS_ACCESS_TOKEN
```

Expected: 默认命令不发网络写请求；`-Apply` 在读取 `NACOS_ACCESS_TOKEN` 后才发布，并以 Client GET 的 SHA-256 与本地 YAML 一致作为单项成功证据。

- [ ] **Step 5: Commit**

```bash
git add RuoYi-Cloud/config/nacos RuoYi-Cloud/docker RuoYi-Cloud/bin
git commit -m "feat(bootstrap): [M1-CONFIG-001] configure service boundaries"
```

### Task 5: 最小前端验证、权限与迁移故障验收

**Files:**
- Modify: `RuoYi-Cloud/sql/README.md`
- Create: `docs/handoff/tasks/M1-RUN-001.md`
- Modify: `docs/handoff/README.md`
- Do not modify for this task: `RuoYi-Cloud-Vue3/src/views/login.vue` and other visual/UI files.

**Interfaces:**
- Consumes: 已有已启动的前后端、已初始化数据库、管理员和两个明确角色开发者账户。
- Produces: 登录、退出、角色菜单、直接越权请求和后端接口可达的最小浏览器结论，以及 M1-01 至 M1-08 的通过/失败/阻塞记录。

- [ ] **Step 1: 先使用已启动前端验证既有登录链路，不改写登录页**

Run: 在浏览器打开当前前端地址，以已存在的测试身份登录并调用一个受保护的 system API。

Expected: 页面可加载、登录成功后可读取菜单；本步不产生 UI 改动。

- [ ] **Step 2: 浏览器验证正确登录、错误密码、停用账号、退出后的旧 token、角色菜单和直接越权请求**

```text
正确身份 -> 登录并读取菜单
退出 -> 用旧 token 请求受保护路由 -> 401/403
低权限开发者 -> 直接请求高权限路由 -> 403
```

- [ ] **Step 3: 在隔离库执行 checksum 负向验证；不得在本地工作库 clean、repair 或改写已应用 V1**

Expected: 被改动的已应用迁移明确失败；恢复原文件后隔离环境保留现场并重新从干净隔离库执行。

- [ ] **Step 4: 更新唯一交接清单并提交实际验收结果**

```bash
git add RuoYi-Cloud/sql/README.md docs/handoff
git commit -m "test(m1): [M1-OPS-001] record bootstrap acceptance"
```

## M2 接续边界

M1 验收通过后，按 `M2-ASSET-001 → M2-PROCESS-001 → M2-PUBLISH-001 → M2-VOICE-001 → M2-ACCEPT-001` 执行。每项在开工前从阶段设计、接口说明与数据库说明导出独立实施计划；不得在 M1 骨架任务中预建 M2 页面、外部计费调用或声音克隆功能。
