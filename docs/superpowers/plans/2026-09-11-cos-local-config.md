# COS Local Config Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Configure the existing system COS adapter for the specified private Guangzhou bucket while keeping real credentials in an ignored local file that the user fills manually.

**Architecture:** The existing `CosStorageConfiguration` and `CosObjectStorage` remain unchanged. The system classpath configuration imports an optional `application-local.yml`; a committed example documents every COS property, while the real local file is ignored and begins with COS disabled until both user credentials are filled.

**Tech Stack:** Spring Boot 3 configuration import, Tencent COS Java SDK, Maven/JUnit, Git ignore rules.

**Spec:** `docs/superpowers/specs/2026-09-11-cos-and-database-bootstrap-design.md`

## Global Constraints

- Use the existing COS adapter and official SDK; do not add another storage abstraction or browser upload path.
- Use region `ap-guangzhou`, bucket `ln-virtual-human-platform-1411676391`, HTTPS, private-object signed reads, and a 900-second read URL lifetime.
- Never commit, print, or put real SecretId/SecretKey in templates, tests, reports, logs, Docker Compose, Nacos, or database rows.
- Create the real ignored file at `RuoYi-Cloud/ruoyi-modules/ruoyi-system/src/main/resources/application-local.yml`; it must contain blank credential values and `enabled: false`.
- Follow the repository's minimal-test rule: use existing tests and targeted configuration validation; do not add unrelated tests.

---

### Task 1: Local COS configuration contract

**Files:**
- Modify: `.gitignore`
- Modify: `RuoYi-Cloud/ruoyi-modules/ruoyi-system/src/main/resources/application.yml`
- Create: `RuoYi-Cloud/ruoyi-modules/ruoyi-system/src/main/resources/application-local.yml.example`
- Create (ignored, not committed): `RuoYi-Cloud/ruoyi-modules/ruoyi-system/src/main/resources/application-local.yml`
- Modify: `RuoYi-Cloud/config/nacos/README.md`
- Test: `RuoYi-Cloud/ruoyi-modules/ruoyi-system/src/test/java/com/ruoyi/system/storage/CosObjectStorageTest.java`

**Interfaces:**
- Consumes: `CosStorageProperties` binding prefix `platform.storage.cos` and the existing `@ConditionalOnProperty` COS client configuration.
- Produces: a local-only YAML override that supplies `enabled`, `region`, `bucket`, `secret-id`, `secret-key`, `session-token`, and `read-url-seconds` without process environment variables.

- [ ] **Step 1: Add the ignored-file rule and prove it is ignored**

Add this exact line to `.gitignore`:

```gitignore
RuoYi-Cloud/ruoyi-modules/ruoyi-system/src/main/resources/application-local.yml
```

Run:

```powershell
git check-ignore -v RuoYi-Cloud/ruoyi-modules/ruoyi-system/src/main/resources/application-local.yml
```

Expected: the new `.gitignore` rule is reported.

- [ ] **Step 2: Make the main application import the optional local file**

Prepend the following configuration to `application.yml`; retain the existing environment-backed defaults below it for deployment compatibility:

```yaml
spring:
  config:
    import: optional:classpath:application-local.yml
```

The imported file must override the same `platform.storage.cos` properties when present.

- [ ] **Step 3: Create the safe example and the real blank local file**

Write the same safe content to `application-local.yml.example` and the ignored `application-local.yml`:

```yaml
platform:
  storage:
    cos:
      enabled: false
      region: ap-guangzhou
      bucket: ln-virtual-human-platform-1411676391
      secret-id: ""
      secret-key: ""
      session-token: ""
      read-url-seconds: 900
```

Do not replace either empty credential value. The user will later set `enabled: true`, `secret-id`, and `secret-key` in the ignored file.

- [ ] **Step 4: Update the local-configuration instructions**

Replace the COS paragraph in `RuoYi-Cloud/config/nacos/README.md` with a short statement that identifies `application-local.yml` as the exact manual credential file, states it is ignored, names the required three edits (`enabled`, `secret-id`, `secret-key`), and retains the private-bucket signed-read/CORS limitations.

- [ ] **Step 5: Verify the adapter and secret boundary**

Run:

```powershell
mvn -B -ntp -pl ruoyi-modules/ruoyi-system -am -Dtest=CosObjectStorageTest test
git check-ignore -v RuoYi-Cloud/ruoyi-modules/ruoyi-system/src/main/resources/application-local.yml
git diff --check
git status --short
```

Expected: COS adapter tests pass; the real file is ignored; the example contains only blank secret fields; no whitespace errors.

- [ ] **Step 6: Commit only tracked configuration and documentation**

Stage `.gitignore`, `application.yml`, `application-local.yml.example`, and the Nacos README by exact paths; do not stage `application-local.yml`. Commit message:

```text
feat(storage): [M1-DB-001] 配置本地COS私密凭据文件
```

