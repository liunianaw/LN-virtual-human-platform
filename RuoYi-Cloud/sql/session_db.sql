/*
 Navicat Premium Dump SQL

 Source Server         : localhost_3306
 Source Server Type    : MySQL
 Source Server Version : 80046 (8.0.46)
 Source Host           : localhost:3306
 Source Schema         : session_db

 Target Server Type    : MySQL
 Target Server Version : 80046 (8.0.46)
 File Encoding         : 65001

 Date: 09/10/2026 21:51:58
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for flyway_schema_history
-- ----------------------------
DROP TABLE IF EXISTS `flyway_schema_history`;
CREATE TABLE `flyway_schema_history`  (
  `installed_rank` int NOT NULL,
  `version` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `description` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `script` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `checksum` int NULL DEFAULT NULL,
  `installed_by` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `installed_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `execution_time` int NOT NULL,
  `success` tinyint(1) NOT NULL,
  PRIMARY KEY (`installed_rank`) USING BTREE,
  INDEX `flyway_schema_history_s_idx`(`success` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_api_idempotency
-- ----------------------------
DROP TABLE IF EXISTS `s_api_idempotency`;
CREATE TABLE `s_api_idempotency`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '所属账号',
  `scope` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '操作、应用、Session/身份及签发来源的规范对象SHA-256小写十六进制，64字符',
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '后端传入幂等请求ID',
  `request_hash` binary(32) NOT NULL COMMENT '规范化业务参数SHA-256；秘密字段先做服务端keyed digest，不存正文',
  `resource_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT 'SESSION_GRANT/REVOCATION；按接口白名单扩展',
  `resource_id` bigint NULL DEFAULT NULL COMMENT 's_session_grant.id或撤销事件s_outbox.id；不是字符串event_id',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'PROCESSING/SUCCEEDED/FAILED',
  `error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '安全错误',
  `expires_at` datetime(3) NOT NULL COMMENT '至少保留24小时并覆盖执行/补偿期；未完成不删除',
  `result_count` int NULL DEFAULT NULL COMMENT 'Persisted revocation count for idempotent response reconstruction',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_s_api_idempotency_1`(`account_id` ASC, `scope` ASC, `request_id` ASC) USING BTREE,
  INDEX `idx_s_api_idempotency_1`(`expires_at` ASC) USING BTREE,
  CONSTRAINT `ck_s_api_idempotency_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_s_api_idempotency_id` CHECK (`id` > 0),
  CONSTRAINT `ck_s_api_idempotency_resource_id` CHECK (`resource_id` > 0),
  CONSTRAINT `ck_s_api_idempotency_status` CHECK (`status` in (_utf8mb4'PROCESSING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '会话HTTP API幂等结果' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_cleanup_job
-- ----------------------------
DROP TABLE IF EXISTS `s_cleanup_job`;
CREATE TABLE `s_cleanup_job`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '归属账号',
  `session_id` bigint NOT NULL COMMENT '删除对象',
  `reason` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'USER_DELETE/EXPIRED',
  `state` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'PENDING/DELETING/RELEASING/DONE/FAILED',
  `cursor_id` bigint NULL DEFAULT NULL COMMENT '分批删除游标',
  `stage` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '当前子表或对象清理步骤',
  `attempt_count` int NOT NULL DEFAULT 0 COMMENT '默认0',
  `next_run_at` datetime(3) NULL DEFAULT NULL COMMENT '重试时间',
  `lease_owner` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '执行者',
  `lease_expires_at` datetime(3) NULL DEFAULT NULL COMMENT '租约',
  `error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '安全错误',
  `finished_at` datetime(3) NULL DEFAULT NULL COMMENT '完成时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_s_cleanup_job_1`(`session_id` ASC) USING BTREE,
  INDEX `idx_s_cleanup_job_1`(`state` ASC, `next_run_at` ASC) USING BTREE,
  INDEX `idx_s_cleanup_job_2`(`state` ASC, `lease_expires_at` ASC) USING BTREE,
  CONSTRAINT `ck_s_cleanup_job_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_s_cleanup_job_attempt_count` CHECK (`attempt_count` >= 0),
  CONSTRAINT `ck_s_cleanup_job_cursor_id` CHECK (`cursor_id` > 0),
  CONSTRAINT `ck_s_cleanup_job_id` CHECK (`id` > 0),
  CONSTRAINT `ck_s_cleanup_job_reason` CHECK (`reason` in (_utf8mb4'USER_DELETE',_utf8mb4'EXPIRED')),
  CONSTRAINT `ck_s_cleanup_job_session_id` CHECK (`session_id` > 0),
  CONSTRAINT `ck_s_cleanup_job_state` CHECK (`state` in (_utf8mb4'PENDING',_utf8mb4'DELETING',_utf8mb4'RELEASING',_utf8mb4'DONE',_utf8mb4'FAILED'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '会话删除与引用释放任务' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_inbox
-- ----------------------------
DROP TABLE IF EXISTS `s_inbox`;
CREATE TABLE `s_inbox`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '事件归属账号',
  `consumer_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '消费逻辑标识',
  `event_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '上游事件ID',
  `payload_hash` binary(32) NOT NULL COMMENT '防止同事件ID不同内容',
  `processed_at` datetime(3) NOT NULL COMMENT '本地业务事务完成时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_s_inbox_1`(`consumer_name` ASC, `event_id` ASC) USING BTREE,
  INDEX `idx_s_inbox_1`(`processed_at` ASC) USING BTREE,
  CONSTRAINT `ck_s_inbox_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_s_inbox_id` CHECK (`id` > 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '消费事件去重' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_job_lease
-- ----------------------------
DROP TABLE IF EXISTS `s_job_lease`;
CREATE TABLE `s_job_lease`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `job_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '清理/投递/补偿扫描任务标识',
  `lease_owner` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '当前执行实例',
  `lease_epoch` bigint NOT NULL DEFAULT 0 COMMENT '默认0；领取代数',
  `lease_expires_at` datetime(3) NULL DEFAULT NULL COMMENT '租约到期',
  `last_completed_at` datetime(3) NULL DEFAULT NULL COMMENT '上次完成',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_s_job_lease_1`(`job_key` ASC) USING BTREE,
  INDEX `idx_s_job_lease_1`(`lease_expires_at` ASC) USING BTREE,
  CONSTRAINT `ck_s_job_lease_id` CHECK (`id` > 0),
  CONSTRAINT `ck_s_job_lease_lease_epoch` CHECK (`lease_epoch` >= 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '持久扫描任务租约' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_migration_audit
-- ----------------------------
DROP TABLE IF EXISTS `s_migration_audit`;
CREATE TABLE `s_migration_audit`  (
  `migration_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `metric_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `metric_value` bigint NOT NULL,
  `recorded_at` datetime(3) NOT NULL,
  PRIMARY KEY (`migration_key`, `metric_key`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_operation
-- ----------------------------
DROP TABLE IF EXISTS `s_operation`;
CREATE TABLE `s_operation`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '归属账号',
  `session_id` bigint NOT NULL COMMENT '所属会话',
  `turn_id` bigint NULL DEFAULT NULL COMMENT '独立ASR识别时允许为空',
  `client_request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '操作幂等ID；内部操作由服务确定性生成',
  `operation_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'LLM/ASR/TTS/TOOL/CONTEXT',
  `ordinal` int NOT NULL COMMENT '同类型的段号/调用序号',
  `config_resource_id` bigint NULL DEFAULT NULL COMMENT 'Voice/Skill/Relay版本ID，按类型解释',
  `provider_request_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '上游请求ID',
  `quota_reservation_id` bigint NULL DEFAULT NULL COMMENT '官方TTS平台预占ID',
  `status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/UNKNOWN',
  `playback_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT 'WAITING/STARTED/ENDED/FAILED/SKIPPED/STOPPED/UNKNOWN；仅TTS，其他操作为空',
  `input_char_count` bigint NULL DEFAULT NULL COMMENT 'TTS计额字符数，不保存纯播报正文',
  `input_hash` binary(32) NULL DEFAULT NULL COMMENT '文本或参数摘要',
  `result_summary` json NULL COMMENT '白名单状态/覆盖范围/字节数等，不放原始结果或URL查询串',
  `started_at` datetime(3) NULL DEFAULT NULL COMMENT '开始时间',
  `finished_at` datetime(3) NULL DEFAULT NULL COMMENT '完成时间',
  `error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '安全错误码',
  `tts_dispatch_state` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'NONE',
  `tts_owner` varchar(36) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `tts_reservation_known` tinyint(1) NOT NULL DEFAULT 0,
  `tts_deadline_at` datetime(3) NULL DEFAULT NULL,
  `settlement_outcome` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL,
  `settlement_status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'NONE',
  `settlement_attempts` int NOT NULL DEFAULT 0,
  `settlement_next_at` datetime(3) NULL DEFAULT NULL,
  `settlement_error` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_s_operation_1`(`session_id` ASC, `client_request_id` ASC) USING BTREE,
  INDEX `idx_s_operation_1`(`account_id` ASC, `session_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_s_operation_2`(`turn_id` ASC, `operation_type` ASC, `ordinal` ASC) USING BTREE,
  INDEX `idx_s_operation_3`(`status` ASC, `started_at` ASC) USING BTREE,
  INDEX `idx_s_operation_settlement`(`settlement_status` ASC, `settlement_next_at` ASC, `id` ASC) USING BTREE,
  INDEX `idx_s_operation_tts_recovery`(`tts_dispatch_state` ASC, `tts_deadline_at` ASC, `id` ASC) USING BTREE,
  CONSTRAINT `fk_s_operation_turn_id` FOREIGN KEY (`turn_id`) REFERENCES `s_turn` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_s_operation_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_s_operation_config_resource_id` CHECK (`config_resource_id` > 0),
  CONSTRAINT `ck_s_operation_id` CHECK (`id` > 0),
  CONSTRAINT `ck_s_operation_input_char_count` CHECK (`input_char_count` >= 0),
  CONSTRAINT `ck_s_operation_operation_type` CHECK (`operation_type` in (_utf8mb4'LLM',_utf8mb4'ASR',_utf8mb4'TTS',_utf8mb4'TOOL',_utf8mb4'CONTEXT')),
  CONSTRAINT `ck_s_operation_ordinal` CHECK (`ordinal` >= 0),
  CONSTRAINT `ck_s_operation_playback_status` CHECK (`playback_status` in (_utf8mb4'WAITING',_utf8mb4'STARTED',_utf8mb4'ENDED',_utf8mb4'FAILED',_utf8mb4'SKIPPED',_utf8mb4'STOPPED',_utf8mb4'UNKNOWN')),
  CONSTRAINT `ck_s_operation_playback_type` CHECK (((`operation_type` = _utf8mb4'TTS') and (`playback_status` is not null)) or ((`operation_type` <> _utf8mb4'TTS') and (`playback_status` is null))),
  CONSTRAINT `ck_s_operation_quota_reservation_id` CHECK (`quota_reservation_id` > 0),
  CONSTRAINT `ck_s_operation_session_id` CHECK (`session_id` > 0),
  CONSTRAINT `ck_s_operation_settlement` CHECK (`settlement_status` in (_utf8mb4'NONE',_utf8mb4'PENDING',_utf8mb4'DONE',_utf8mb4'REVIEW_REQUIRED')),
  CONSTRAINT `ck_s_operation_settlement_outcome` CHECK (`settlement_outcome` in (_utf8mb4'SETTLE',_utf8mb4'RELEASE',_utf8mb4'REVIEW')),
  CONSTRAINT `ck_s_operation_status` CHECK (`status` in (_utf8mb4'QUEUED',_utf8mb4'RUNNING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED',_utf8mb4'CANCELLED',_utf8mb4'UNKNOWN')),
  CONSTRAINT `ck_s_operation_tts_dispatch` CHECK (`tts_dispatch_state` in (_utf8mb4'NONE',_utf8mb4'PREPARED',_utf8mb4'DISPATCHING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED')),
  CONSTRAINT `ck_s_operation_turn_id` CHECK (`turn_id` > 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '本轮外部操作及分段状态' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_outbox
-- ----------------------------
DROP TABLE IF EXISTS `s_outbox`;
CREATE TABLE `s_outbox`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '业务归属账号；纯系统事件使用操作管理员账号',
  `event_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '全局事件ID',
  `event_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '业务事件类型',
  `aggregate_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '聚合实体类型',
  `aggregate_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '实体ID',
  `schema_version` int NOT NULL COMMENT '消息协议版本',
  `trace_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '追踪ID',
  `payload` json NOT NULL COMMENT '白名单标识、状态与必要用量，不含秘密/正文/素材',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'PENDING/SENDING/SENT',
  `attempt_count` int NOT NULL DEFAULT 0 COMMENT '默认0',
  `next_run_at` datetime(3) NULL DEFAULT NULL COMMENT '发送时间',
  `lease_owner` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '发送者',
  `lease_expires_at` datetime(3) NULL DEFAULT NULL COMMENT '租约',
  `published_at` datetime(3) NULL DEFAULT NULL COMMENT '发布确认时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_s_outbox_1`(`event_id` ASC) USING BTREE,
  INDEX `idx_s_outbox_1`(`status` ASC, `next_run_at` ASC) USING BTREE,
  INDEX `idx_s_outbox_2`(`status` ASC, `lease_expires_at` ASC) USING BTREE,
  CONSTRAINT `ck_s_outbox_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_s_outbox_attempt_count` CHECK (`attempt_count` >= 0),
  CONSTRAINT `ck_s_outbox_id` CHECK (`id` > 0),
  CONSTRAINT `ck_s_outbox_schema_version` CHECK (`schema_version` > 0),
  CONSTRAINT `ck_s_outbox_status` CHECK (`status` in (_utf8mb4'PENDING',_utf8mb4'SENDING',_utf8mb4'SENT'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '本地事务事件发件箱' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_principal
-- ----------------------------
DROP TABLE IF EXISTS `s_principal`;
CREATE TABLE `s_principal`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '开发者账号，跨库引用sys_user',
  `application_id` bigint NOT NULL COMMENT '平台应用ID',
  `principal_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'BUSINESS/DEBUG',
  `external_user_id` varchar(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '开发者后端验证的稳定用户ID；区分大小写',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'ACTIVE/DISABLED',
  `auth_epoch` bigint NOT NULL DEFAULT 1 COMMENT '默认1；退出/撤销全部会话授权递增',
  `last_seen_at` datetime(3) NOT NULL COMMENT '最近业务活动',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_s_principal_1`(`account_id` ASC, `application_id` ASC, `principal_type` ASC, `external_user_id` ASC) USING BTREE,
  INDEX `idx_s_principal_1`(`account_id` ASC, `application_id` ASC, `status` ASC) USING BTREE,
  CONSTRAINT `ck_s_principal_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_s_principal_application_id` CHECK (`application_id` > 0),
  CONSTRAINT `ck_s_principal_auth_epoch` CHECK (`auth_epoch` > 0),
  CONSTRAINT `ck_s_principal_id` CHECK (`id` > 0),
  CONSTRAINT `ck_s_principal_principal_type` CHECK (`principal_type` in (_utf8mb4'BUSINESS',_utf8mb4'DEBUG')),
  CONSTRAINT `ck_s_principal_status` CHECK (`status` in (_utf8mb4'ACTIVE',_utf8mb4'DISABLED'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '应用业务身份与持久撤销版本' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_runtime_ticket
-- ----------------------------
DROP TABLE IF EXISTS `s_runtime_ticket`;
CREATE TABLE `s_runtime_ticket`  (
  `id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `ticket_hash` binary(32) NOT NULL,
  `account_id` bigint NOT NULL,
  `application_id` bigint NOT NULL,
  `session_id` bigint NOT NULL,
  `session_snapshot_id` bigint NOT NULL,
  `voice_version_id` bigint NOT NULL,
  `provider_kind` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `provider_voice_ref` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `official_service_id` bigint NULL DEFAULT NULL,
  `official_service_revision` bigint NULL DEFAULT NULL,
  `purpose` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `expires_at` datetime(3) NOT NULL,
  `consumed_at` datetime(3) NULL DEFAULT NULL,
  `grant_id` bigint NULL DEFAULT NULL,
  `expected_connection_epoch` bigint NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_s_runtime_ticket_hash`(`ticket_hash` ASC) USING BTREE,
  INDEX `idx_s_runtime_ticket_expiry`(`status` ASC, `expires_at` ASC) USING BTREE,
  INDEX `idx_s_runtime_ticket_grant`(`grant_id` ASC) USING BTREE,
  CONSTRAINT `ck_s_runtime_ticket_account` CHECK (`account_id` > 0),
  CONSTRAINT `ck_s_runtime_ticket_application` CHECK (`application_id` > 0),
  CONSTRAINT `ck_s_runtime_ticket_expected_epoch` CHECK ((`expected_connection_epoch` is null) or (`expected_connection_epoch` > 0)),
  CONSTRAINT `ck_s_runtime_ticket_id` CHECK (`id` > 0),
  CONSTRAINT `ck_s_runtime_ticket_purpose` CHECK (`purpose` in (_utf8mb4'CONNECT',_utf8mb4'REAUTHORIZE')),
  CONSTRAINT `ck_s_runtime_ticket_session` CHECK (`session_id` > 0),
  CONSTRAINT `ck_s_runtime_ticket_snapshot` CHECK (`session_snapshot_id` > 0),
  CONSTRAINT `ck_s_runtime_ticket_status` CHECK (`status` in (_utf8mb4'ACTIVE',_utf8mb4'CONSUMED',_utf8mb4'EXPIRED')),
  CONSTRAINT `ck_s_runtime_ticket_voice` CHECK (`voice_version_id` > 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '浏览器运行时一次性连接票据' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_session
-- ----------------------------
DROP TABLE IF EXISTS `s_session`;
CREATE TABLE `s_session`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '开发者账号',
  `application_id` bigint NOT NULL COMMENT '应用',
  `principal_id` bigint NOT NULL COMMENT 's_principal.id',
  `session_snapshot_id` bigint NOT NULL COMMENT 's_session_snapshot.id，不可变运行配置',
  `create_request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '新建会话幂等键',
  `reference_operation_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '平台引用预留操作ID',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'CREATING/ACTIVE/DELETING/DELETED/FAILED',
  `auth_epoch` bigint NOT NULL DEFAULT 1 COMMENT '默认1；仅撤销本Session时递增',
  `connection_epoch` bigint NOT NULL DEFAULT 0 COMMENT '默认0；持久连接代数',
  `active_turn_id` bigint NULL DEFAULT NULL COMMENT '当前轮次，同库逻辑关联',
  `next_turn_no` bigint NOT NULL DEFAULT 1 COMMENT '默认1',
  `next_message_seq` bigint NOT NULL DEFAULT 1 COMMENT '默认1',
  `last_activity_at` datetime(3) NOT NULL COMMENT '成功用户请求/业务Context更新；心跳不续期',
  `expires_at` datetime(3) NOT NULL COMMENT '最后活动时间加30天',
  `deleted_at` datetime(3) NULL DEFAULT NULL COMMENT '逻辑不可恢复时间',
  `revision` bigint NOT NULL DEFAULT 1 COMMENT '默认1；状态/连接/轮次并发控制',
  `create_request_hash` binary(32) NULL DEFAULT NULL COMMENT 'BUSINESS create parameters; optional credential included only as keyed digest',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_s_session_1`(`account_id` ASC, `application_id` ASC, `principal_id` ASC, `create_request_id` ASC) USING BTREE,
  UNIQUE INDEX `uq_s_session_snapshot`(`session_snapshot_id` ASC) USING BTREE,
  INDEX `idx_s_session_1`(`account_id` ASC, `application_id` ASC, `principal_id` ASC, `last_activity_at` ASC) USING BTREE,
  INDEX `idx_s_session_2`(`status` ASC, `expires_at` ASC) USING BTREE,
  INDEX `idx_s_session_fk_principal_id`(`principal_id` ASC) USING BTREE,
  CONSTRAINT `fk_s_session_principal_id` FOREIGN KEY (`principal_id`) REFERENCES `s_principal` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_s_session_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_s_session_active_turn_id` CHECK (`active_turn_id` > 0),
  CONSTRAINT `ck_s_session_application_id` CHECK (`application_id` > 0),
  CONSTRAINT `ck_s_session_auth_epoch` CHECK (`auth_epoch` > 0),
  CONSTRAINT `ck_s_session_connection_epoch` CHECK (`connection_epoch` >= 0),
  CONSTRAINT `ck_s_session_id` CHECK (`id` > 0),
  CONSTRAINT `ck_s_session_next_message_seq` CHECK (`next_message_seq` > 0),
  CONSTRAINT `ck_s_session_next_turn_no` CHECK (`next_turn_no` > 0),
  CONSTRAINT `ck_s_session_principal_id` CHECK (`principal_id` > 0),
  CONSTRAINT `ck_s_session_revision` CHECK (`revision` > 0),
  CONSTRAINT `ck_s_session_snapshot_ref` CHECK (`session_snapshot_id` > 0),
  CONSTRAINT `ck_s_session_status` CHECK (`status` in (_utf8mb4'CREATING',_utf8mb4'ACTIVE',_utf8mb4'DELETING',_utf8mb4'DELETED',_utf8mb4'FAILED'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '会话归属与恢复' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_session_context
-- ----------------------------
DROP TABLE IF EXISTS `s_session_context`;
CREATE TABLE `s_session_context`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '归属账号',
  `session_id` bigint NOT NULL COMMENT '所属会话',
  `content` json NOT NULL COMMENT '持久业务Context，禁止混入凭证和身份授权字段',
  `size_bytes` int NOT NULL COMMENT '序列化UTF-8大小，应用层限制',
  `revision` bigint NOT NULL DEFAULT 1 COMMENT '默认1；条件更新防覆盖',
  `updated_by_key_id` bigint NOT NULL COMMENT 'p_access_key.id，记录后端修改来源',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_s_session_context_1`(`session_id` ASC) USING BTREE,
  INDEX `idx_s_session_context_1`(`account_id` ASC, `session_id` ASC) USING BTREE,
  CONSTRAINT `fk_s_session_context_session_id` FOREIGN KEY (`session_id`) REFERENCES `s_session` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_s_session_context_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_s_session_context_id` CHECK (`id` > 0),
  CONSTRAINT `ck_s_session_context_revision` CHECK (`revision` > 0),
  CONSTRAINT `ck_s_session_context_session_id` CHECK (`session_id` > 0),
  CONSTRAINT `ck_s_session_context_size_bytes` CHECK (`size_bytes` >= 0),
  CONSTRAINT `ck_s_session_context_updated_by_key_id` CHECK (`updated_by_key_id` > 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '后端维护的Session Context' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_session_grant
-- ----------------------------
DROP TABLE IF EXISTS `s_session_grant`;
CREATE TABLE `s_session_grant`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '归属账号',
  `session_id` bigint NOT NULL COMMENT 's_session.id',
  `principal_id` bigint NOT NULL COMMENT 's_principal.id',
  `application_id` bigint NOT NULL COMMENT '应用ID',
  `grant_source` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'BUSINESS_KEY/CONSOLE_DEBUG，须匹配Session的BUSINESS/DEBUG身份',
  `issuer_key_id` bigint NULL DEFAULT NULL COMMENT 'BUSINESS_KEY必填p_access_key.id；CONSOLE_DEBUG为空',
  `token_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'Token唯一标识/JTI；不存完整Token',
  `scopes` json NOT NULL COMMENT '签发的S运行权限；运行时再受固定配置与当前授权限制',
  `account_epoch` bigint NOT NULL COMMENT '签发时账号版本',
  `application_epoch` bigint NOT NULL COMMENT '签发时应用版本',
  `principal_epoch` bigint NOT NULL COMMENT '签发时业务用户版本',
  `session_epoch` bigint NOT NULL COMMENT '签发时Session版本',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'ACTIVE/REVOKED',
  `expires_at` datetime(3) NOT NULL COMMENT '短期过期时间',
  `revoked_at` datetime(3) NULL DEFAULT NULL COMMENT '撤销时间',
  `issuer_key_epoch` bigint NULL DEFAULT NULL COMMENT 'BUSINESS_KEY必填签发Key的auth_epoch；CONSOLE_DEBUG为空',
  `signing_key_version` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT '1' COMMENT 'HMAC signing key version; independent of issuer_key_epoch',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_s_session_grant_1`(`token_id` ASC) USING BTREE,
  INDEX `idx_s_session_grant_1`(`account_id` ASC, `session_id` ASC, `status` ASC) USING BTREE,
  INDEX `idx_s_session_grant_2`(`expires_at` ASC) USING BTREE,
  INDEX `idx_s_session_grant_3`(`issuer_key_id` ASC, `status` ASC) USING BTREE,
  INDEX `idx_s_session_grant_fk_session_id`(`session_id` ASC) USING BTREE,
  CONSTRAINT `fk_s_session_grant_session_id` FOREIGN KEY (`session_id`) REFERENCES `s_session` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_s_session_grant_account_epoch` CHECK (`account_epoch` > 0),
  CONSTRAINT `ck_s_session_grant_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_s_session_grant_application_epoch` CHECK (`application_epoch` > 0),
  CONSTRAINT `ck_s_session_grant_application_id` CHECK (`application_id` > 0),
  CONSTRAINT `ck_s_session_grant_id` CHECK (`id` > 0),
  CONSTRAINT `ck_s_session_grant_issuer` CHECK ((`issuer_key_id` is not null) and (`issuer_key_epoch` is not null)),
  CONSTRAINT `ck_s_session_grant_issuer_key_epoch` CHECK (`issuer_key_epoch` > 0),
  CONSTRAINT `ck_s_session_grant_issuer_key_id` CHECK (`issuer_key_id` > 0),
  CONSTRAINT `ck_s_session_grant_principal_epoch` CHECK (`principal_epoch` > 0),
  CONSTRAINT `ck_s_session_grant_principal_id` CHECK (`principal_id` > 0),
  CONSTRAINT `ck_s_session_grant_session_epoch` CHECK (`session_epoch` > 0),
  CONSTRAINT `ck_s_session_grant_session_id` CHECK (`session_id` > 0),
  CONSTRAINT `ck_s_session_grant_source` CHECK (`grant_source` = _utf8mb4'BUSINESS_KEY'),
  CONSTRAINT `ck_s_session_grant_status` CHECK (`status` in (_utf8mb4'ACTIVE',_utf8mb4'REVOKED'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '短期Session授权' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_session_snapshot
-- ----------------------------
DROP TABLE IF EXISTS `s_session_snapshot`;
CREATE TABLE `s_session_snapshot`  (
  `id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `account_id` bigint NOT NULL,
  `application_id` bigint NOT NULL,
  `application_revision` bigint NOT NULL,
  `legacy_config_ref` bigint NULL DEFAULT NULL,
  `avatar_version_id` bigint NULL DEFAULT NULL,
  `voice_version_id` bigint NULL DEFAULT NULL,
  `system_prompt` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL,
  `developer_config` json NOT NULL,
  `internal_skills` json NOT NULL,
  `allowed_scopes` json NOT NULL,
  `provider_voice_ref` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL,
  `official_service_id` bigint NULL DEFAULT NULL,
  `official_service_revision` bigint NULL DEFAULT NULL,
  `voice_binding` json NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_s_session_snapshot_owner`(`account_id` ASC, `application_id` ASC, `created_at` ASC) USING BTREE,
  CONSTRAINT `ck_s_session_snapshot_account` CHECK (`account_id` > 0),
  CONSTRAINT `ck_s_session_snapshot_application` CHECK (`application_id` > 0),
  CONSTRAINT `ck_s_session_snapshot_id` CHECK (`id` > 0),
  CONSTRAINT `ck_s_session_snapshot_revision` CHECK (`application_revision` > 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = 'Session创建时冻结的Application运行配置' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_temp_object
-- ----------------------------
DROP TABLE IF EXISTS `s_temp_object`;
CREATE TABLE `s_temp_object`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `media_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '运行时音频公开给所属S令牌的逻辑标识',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '归属账号',
  `session_id` bigint NOT NULL COMMENT '所属会话',
  `turn_id` bigint NULL DEFAULT NULL COMMENT 'ASR预输入等可空',
  `operation_id` bigint NULL DEFAULT NULL COMMENT '关联操作',
  `purpose` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'SCREENSHOT/RECORDING/TTS_AUDIO',
  `storage_provider` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '云对象存储适配器',
  `bucket` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '桶',
  `object_key` varchar(768) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ASCII私有对象键',
  `size_bytes` bigint NOT NULL COMMENT '字节数',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'UPLOADING/ACTIVE/DELETE_PENDING/DELETED',
  `expires_at` datetime(3) NOT NULL COMMENT '故障情况下最长生存期',
  `next_delete_at` datetime(3) NULL DEFAULT NULL COMMENT '删除重试时间',
  `delete_attempts` int NOT NULL DEFAULT 0 COMMENT '默认0',
  `last_error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '清理错误码',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_s_temp_object_1`(`storage_provider` ASC, `bucket` ASC, `object_key` ASC) USING BTREE,
  UNIQUE INDEX `uq_s_temp_object_media`(`media_id` ASC) USING BTREE,
  INDEX `idx_s_temp_object_1`(`account_id` ASC, `session_id` ASC) USING BTREE,
  INDEX `idx_s_temp_object_2`(`status` ASC, `expires_at` ASC) USING BTREE,
  INDEX `idx_s_temp_object_3`(`status` ASC, `next_delete_at` ASC) USING BTREE,
  CONSTRAINT `ck_s_temp_object_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_s_temp_object_delete_attempts` CHECK (`delete_attempts` >= 0),
  CONSTRAINT `ck_s_temp_object_id` CHECK (`id` > 0),
  CONSTRAINT `ck_s_temp_object_operation_id` CHECK (`operation_id` > 0),
  CONSTRAINT `ck_s_temp_object_purpose` CHECK (`purpose` in (_utf8mb4'SCREENSHOT',_utf8mb4'RECORDING',_utf8mb4'TTS_AUDIO')),
  CONSTRAINT `ck_s_temp_object_session_id` CHECK (`session_id` > 0),
  CONSTRAINT `ck_s_temp_object_size_bytes` CHECK (`size_bytes` >= 0),
  CONSTRAINT `ck_s_temp_object_status` CHECK (`status` in (_utf8mb4'UPLOADING',_utf8mb4'ACTIVE',_utf8mb4'DELETE_PENDING',_utf8mb4'DELETED')),
  CONSTRAINT `ck_s_temp_object_turn_id` CHECK (`turn_id` > 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '临时对象清理登记' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_tool_invocation
-- ----------------------------
DROP TABLE IF EXISTS `s_tool_invocation`;
CREATE TABLE `s_tool_invocation`  (
  `id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `account_id` bigint NOT NULL,
  `session_id` bigint NOT NULL,
  `skill_id` bigint NOT NULL,
  `idempotency_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `request_hash` binary(32) NOT NULL,
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `http_status` int NULL DEFAULT NULL,
  `result_json` json NULL,
  `error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL,
  `finished_at` datetime(3) NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_s_tool_invocation`(`session_id` ASC, `skill_id` ASC, `idempotency_key` ASC) USING BTREE,
  INDEX `idx_s_tool_invocation_limit`(`session_id` ASC, `skill_id` ASC, `status` ASC, `created_at` ASC) USING BTREE,
  CONSTRAINT `ck_s_tool_invocation_account` CHECK (`account_id` > 0),
  CONSTRAINT `ck_s_tool_invocation_id` CHECK (`id` > 0),
  CONSTRAINT `ck_s_tool_invocation_session` CHECK (`session_id` > 0),
  CONSTRAINT `ck_s_tool_invocation_skill` CHECK (`skill_id` > 0),
  CONSTRAINT `ck_s_tool_invocation_status` CHECK (`status` in (_utf8mb4'PROCESSING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED',_utf8mb4'UNKNOWN'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '开发者后端按Session调用HTTP Tool的幂等与限次事实' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_turn
-- ----------------------------
DROP TABLE IF EXISTS `s_turn`;
CREATE TABLE `s_turn`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '归属账号',
  `session_id` bigint NOT NULL COMMENT '所属会话',
  `turn_no` bigint NOT NULL COMMENT '会话内递增',
  `client_request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '本轮幂等ID',
  `request_hash` binary(32) NOT NULL COMMENT '请求摘要，不保存临时数据正文',
  `turn_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'CHAT/SPEAK',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'RUNNING/COMPLETED/INTERRUPTED/FAILED',
  `text_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'NOT_REQUESTED/RUNNING/COMPLETED/FAILED/INTERRUPTED；SPEAK为NOT_REQUESTED',
  `audio_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'NOT_REQUESTED/RUNNING/COMPLETED/FAILED/INTERRUPTED/UNKNOWN；TTS合成状态',
  `playback_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'NOT_REQUESTED/WAITING/PLAYING/COMPLETED/FAILED/STOPPED/UNKNOWN；播放观测',
  `connection_epoch` bigint NOT NULL COMMENT '接收请求时连接代数',
  `input_source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'TEXT/ASR/DEVELOPER',
  `include_in_history` tinyint NOT NULL DEFAULT 0 COMMENT 'CHAT为1，SPEAK默认0',
  `cancel_reason` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT 'USER_STOP/REPLACED/DISCONNECTED/REVOKED',
  `last_event_seq` bigint NOT NULL DEFAULT 0 COMMENT '默认0；运行事件编号',
  `started_at` datetime(3) NOT NULL COMMENT '开始时间',
  `ended_at` datetime(3) NULL DEFAULT NULL COMMENT '结束时间',
  `error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '安全错误码',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_s_turn_1`(`session_id` ASC, `turn_no` ASC) USING BTREE,
  UNIQUE INDEX `uq_s_turn_2`(`session_id` ASC, `client_request_id` ASC) USING BTREE,
  INDEX `idx_s_turn_1`(`account_id` ASC, `session_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_s_turn_2`(`status` ASC, `started_at` ASC) USING BTREE,
  CONSTRAINT `fk_s_turn_session_id` FOREIGN KEY (`session_id`) REFERENCES `s_session` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_s_turn_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_s_turn_audio_status` CHECK (`audio_status` in (_utf8mb4'NOT_REQUESTED',_utf8mb4'RUNNING',_utf8mb4'COMPLETED',_utf8mb4'FAILED',_utf8mb4'INTERRUPTED',_utf8mb4'UNKNOWN')),
  CONSTRAINT `ck_s_turn_cancel_reason` CHECK (`cancel_reason` in (_utf8mb4'USER_STOP',_utf8mb4'REPLACED',_utf8mb4'DISCONNECTED',_utf8mb4'REVOKED')),
  CONSTRAINT `ck_s_turn_connection_epoch` CHECK (`connection_epoch` >= 0),
  CONSTRAINT `ck_s_turn_id` CHECK (`id` > 0),
  CONSTRAINT `ck_s_turn_include_in_history` CHECK (`include_in_history` in (0,1)),
  CONSTRAINT `ck_s_turn_input_source` CHECK (`input_source` in (_utf8mb4'TEXT',_utf8mb4'ASR',_utf8mb4'DEVELOPER')),
  CONSTRAINT `ck_s_turn_last_event_seq` CHECK (`last_event_seq` >= 0),
  CONSTRAINT `ck_s_turn_playback_status` CHECK (`playback_status` in (_utf8mb4'NOT_REQUESTED',_utf8mb4'WAITING',_utf8mb4'PLAYING',_utf8mb4'COMPLETED',_utf8mb4'FAILED',_utf8mb4'STOPPED',_utf8mb4'UNKNOWN')),
  CONSTRAINT `ck_s_turn_session_id` CHECK (`session_id` > 0),
  CONSTRAINT `ck_s_turn_speak_text` CHECK ((`turn_type` <> _utf8mb4'SPEAK') or (`text_status` = _utf8mb4'NOT_REQUESTED')),
  CONSTRAINT `ck_s_turn_status` CHECK (`status` in (_utf8mb4'RUNNING',_utf8mb4'COMPLETED',_utf8mb4'INTERRUPTED',_utf8mb4'FAILED')),
  CONSTRAINT `ck_s_turn_text_status` CHECK (`text_status` in (_utf8mb4'NOT_REQUESTED',_utf8mb4'RUNNING',_utf8mb4'COMPLETED',_utf8mb4'FAILED',_utf8mb4'INTERRUPTED')),
  CONSTRAINT `ck_s_turn_turn_no` CHECK (`turn_no` > 0),
  CONSTRAINT `ck_s_turn_turn_type` CHECK (`turn_type` in (_utf8mb4'CHAT',_utf8mb4'SPEAK'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '会话轮次' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_voice_attempt
-- ----------------------------
DROP TABLE IF EXISTS `s_voice_attempt`;
CREATE TABLE `s_voice_attempt`  (
  `id` bigint NOT NULL,
  `task_id` bigint NOT NULL,
  `attempt_no` int NOT NULL,
  `provider_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `service_id` bigint NOT NULL,
  `voice_version_id` bigint NOT NULL,
  `model_revision` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `capability_version` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `request_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `dispatch_token_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `state` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'CREATED',
  `worker_instance_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `worker_boot_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `lease_epoch` bigint NOT NULL DEFAULT 1,
  `lease_expires_at` datetime(3) NOT NULL,
  `query_count` int NOT NULL DEFAULT 0,
  `query_next_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `dispatched_at` datetime(3) NULL DEFAULT NULL,
  `accepted_at` datetime(3) NULL DEFAULT NULL,
  `finished_at` datetime(3) NULL DEFAULT NULL,
  `provider_request_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `failure_stage` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `cost_source` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'UNKNOWN',
  `result_summary` json NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_voice_attempt`(`task_id` ASC, `attempt_no` ASC) USING BTREE,
  UNIQUE INDEX `uq_voice_attempt_owner`(`task_id` ASC, `id` ASC) USING BTREE,
  INDEX `idx_voice_attempt_recovery`(`state` ASC, `lease_expires_at` ASC, `id` ASC) USING BTREE,
  INDEX `idx_voice_attempt_query`(`state` ASC, `query_next_at` ASC, `id` ASC) USING BTREE,
  CONSTRAINT `fk_voice_attempt_task` FOREIGN KEY (`task_id`) REFERENCES `s_voice_task` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_voice_attempt_no` CHECK (`attempt_no` in (1,2)),
  CONSTRAINT `ck_voice_attempt_query` CHECK (`query_count` between 0 and 12),
  CONSTRAINT `ck_voice_attempt_state` CHECK (`state` in (_utf8mb4'CREATED',_utf8mb4'QUEUED',_utf8mb4'DISPATCHING',_utf8mb4'ACCEPTED',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED',_utf8mb4'CANCELLED',_utf8mb4'UNKNOWN'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_voice_event
-- ----------------------------
DROP TABLE IF EXISTS `s_voice_event`;
CREATE TABLE `s_voice_event`  (
  `attempt_id` bigint NOT NULL,
  `event_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `payload_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`attempt_id`, `event_id`) USING BTREE,
  CONSTRAINT `fk_voice_event_attempt` FOREIGN KEY (`attempt_id`) REFERENCES `s_voice_attempt` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for s_voice_task
-- ----------------------------
DROP TABLE IF EXISTS `s_voice_task`;
CREATE TABLE `s_voice_task`  (
  `id` bigint NOT NULL,
  `account_id` bigint NOT NULL,
  `purpose` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `source_key` varchar(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `request_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `voice_version_id` bigint NOT NULL,
  `binding_snapshot` json NOT NULL,
  `policy_version` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'QUEUED',
  `revision` bigint NOT NULL DEFAULT 1,
  `winner_attempt_id` bigint NULL DEFAULT NULL,
  `session_id` bigint NULL DEFAULT NULL,
  `turn_id` bigint NULL DEFAULT NULL,
  `operation_id` bigint NULL DEFAULT NULL,
  `connection_epoch` bigint NULL DEFAULT NULL,
  `generation` bigint NULL DEFAULT NULL,
  `grant_id` bigint NULL DEFAULT NULL,
  `owner` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `cancel_requested_at` datetime(3) NULL DEFAULT NULL,
  `deadline_at` datetime(3) NOT NULL,
  `input_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `input_char_count` int NOT NULL,
  `error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `result_reference` json NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `finished_at` datetime(3) NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_voice_source`(`account_id` ASC, `purpose` ASC, `source_key` ASC) USING BTREE,
  UNIQUE INDEX `uq_voice_operation`(`operation_id` ASC) USING BTREE,
  INDEX `idx_voice_task_recovery`(`status` ASC, `deadline_at` ASC, `id` ASC) USING BTREE,
  INDEX `idx_voice_task_account`(`account_id` ASC, `created_at` ASC, `id` ASC) USING BTREE,
  INDEX `fk_voice_task_winner`(`id` ASC, `winner_attempt_id` ASC) USING BTREE,
  INDEX `idx_voice_diagnostic_page`(`created_at` ASC, `id` ASC) USING BTREE,
  INDEX `idx_voice_diagnostic_status`(`status` ASC, `created_at` ASC, `id` ASC) USING BTREE,
  CONSTRAINT `fk_voice_task_operation` FOREIGN KEY (`operation_id`) REFERENCES `s_operation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_voice_task_winner` FOREIGN KEY (`id`, `winner_attempt_id`) REFERENCES `s_voice_attempt` (`task_id`, `id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_voice_task_context` CHECK (((`purpose` = _utf8mb4'BUSINESS') and (`session_id` is not null) and (`turn_id` is not null) and (`operation_id` is not null) and (`connection_epoch` is not null) and (`generation` is not null) and (`grant_id` is not null)) or ((`purpose` = _utf8mb4'AUDITION') and (`session_id` is null) and (`turn_id` is null) and (`operation_id` is null) and (`connection_epoch` is null) and (`generation` is null) and (`grant_id` is null))),
  CONSTRAINT `ck_voice_task_identity` CHECK ((`id` > 0) and (`account_id` > 0) and (`voice_version_id` > 0) and (`revision` in (1,2)) and (`input_char_count` between 1 and 200)),
  CONSTRAINT `ck_voice_task_status` CHECK (`status` in (_utf8mb4'QUEUED',_utf8mb4'RUNNING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED',_utf8mb4'CANCELLED',_utf8mb4'UNKNOWN'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
