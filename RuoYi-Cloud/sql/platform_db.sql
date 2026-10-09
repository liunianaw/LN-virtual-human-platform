/*
 Navicat Premium Dump SQL

 Source Server         : localhost_3306
 Source Server Type    : MySQL
 Source Server Version : 80046 (8.0.46)
 Source Host           : localhost:3306
 Source Schema         : platform_db

 Target Server Type    : MySQL
 Target Server Version : 80046 (8.0.46)
 File Encoding         : 65001

 Date: 09/10/2026 21:51:39
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
-- Table structure for p_access_key
-- ----------------------------
DROP TABLE IF EXISTS `p_access_key`;
CREATE TABLE `p_access_key`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数 ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT 'sys_user.user_id',
  `application_id` bigint NULL DEFAULT NULL COMMENT 'APPLICATION类型必填，MANAGEMENT类型为空',
  `key_type` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'MANAGEMENT/APPLICATION',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '凭证显示名称',
  `public_id` varchar(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '对外可见且全局唯一的凭证标识',
  `secret_hash` binary(32) NOT NULL COMMENT '高熵Secret摘要或带服务端pepper的摘要',
  `hash_key_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '摘要方案/pepper版本',
  `display_suffix` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '脱敏显示后缀',
  `scopes` json NOT NULL COMMENT '本Key接入权限词表；APPLICATION存sessions:*，不存S运行权限',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'ACTIVE/DISABLED/DELETED',
  `expires_at` datetime(3) NULL DEFAULT NULL COMMENT '可选有效期',
  `revoked_at` datetime(3) NULL DEFAULT NULL COMMENT '撤销时间',
  `last_used_at` datetime(3) NULL DEFAULT NULL COMMENT '最近使用，异步合并更新',
  `auth_epoch` bigint NOT NULL DEFAULT 1 COMMENT '默认1；轮换、停用、撤销递增，重新启用不复活旧授权',
  `active_application_id` bigint GENERATED ALWAYS AS ((case when ((`key_type` = _utf8mb4'APPLICATION') and (`status` = _utf8mb4'ACTIVE')) then `application_id` else NULL end)) STORED NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_access_key_1`(`public_id` ASC) USING BTREE,
  UNIQUE INDEX `uq_p_access_key_active_application`(`active_application_id` ASC) USING BTREE,
  INDEX `idx_p_access_key_1`(`account_id` ASC, `key_type` ASC, `status` ASC) USING BTREE,
  INDEX `idx_p_access_key_2`(`application_id` ASC, `status` ASC) USING BTREE,
  CONSTRAINT `ck_p_access_key_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_access_key_application` CHECK (`application_id` is not null),
  CONSTRAINT `ck_p_access_key_application_id` CHECK (`application_id` > 0),
  CONSTRAINT `ck_p_access_key_auth_epoch` CHECK (`auth_epoch` > 0),
  CONSTRAINT `ck_p_access_key_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_access_key_key_type` CHECK (`key_type` = _utf8mb4'APPLICATION'),
  CONSTRAINT `ck_p_access_key_status` CHECK (`status` in (_utf8mb4'ACTIVE',_utf8mb4'DISABLED',_utf8mb4'DELETED'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '管理Key与Application Secret' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_access_key_audit
-- ----------------------------
DROP TABLE IF EXISTS `p_access_key_audit`;
CREATE TABLE `p_access_key_audit`  (
  `id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `account_id` bigint NOT NULL,
  `key_id` bigint NOT NULL,
  `actor_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `actor_key_id` bigint NULL DEFAULT NULL,
  `action` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_p_access_key_audit_account`(`account_id` ASC, `created_at` ASC) USING BTREE,
  CONSTRAINT `ck_p_access_key_audit_action` CHECK (`action` in (_utf8mb4'ACTIVE',_utf8mb4'DISABLED',_utf8mb4'DELETED')),
  CONSTRAINT `ck_p_access_key_audit_actor` CHECK (`actor_type` = _utf8mb4'CONSOLE')
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_account_limit
-- ----------------------------
DROP TABLE IF EXISTS `p_account_limit`;
CREATE TABLE `p_account_limit`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '所属账号',
  `max_file_bytes` bigint NOT NULL COMMENT '单文件上限',
  `max_sessions` int NOT NULL COMMENT '同时活跃Session上限',
  `max_generation_tasks` int NOT NULL COMMENT '并行生成任务上限',
  `max_turns` int NOT NULL COMMENT '全账号并发轮次上限',
  `revision` bigint NOT NULL DEFAULT 1 COMMENT '默认1；修改修订号',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_account_limit_1`(`account_id` ASC) USING BTREE,
  CONSTRAINT `ck_p_account_limit_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_account_limit_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_account_limit_max_file_bytes` CHECK (`max_file_bytes` >= 0),
  CONSTRAINT `ck_p_account_limit_max_generation_tasks` CHECK (`max_generation_tasks` >= 0),
  CONSTRAINT `ck_p_account_limit_max_sessions` CHECK (`max_sessions` >= 0),
  CONSTRAINT `ck_p_account_limit_max_turns` CHECK (`max_turns` >= 0),
  CONSTRAINT `ck_p_account_limit_revision` CHECK (`revision` > 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '账号运行上限' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_api_idempotency
-- ----------------------------
DROP TABLE IF EXISTS `p_api_idempotency`;
CREATE TABLE `p_api_idempotency`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '所属账号',
  `scope` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '操作、目标及必要身份的规范对象SHA-256小写十六进制，固定64字符',
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '后端传入幂等请求ID',
  `request_hash` binary(32) NOT NULL COMMENT '规范化业务参数SHA-256；秘密字段先做服务端keyed digest，不存正文',
  `resource_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '创建资源类型',
  `resource_id` bigint NULL DEFAULT NULL COMMENT '已创建资源ID',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'PROCESSING/SUCCEEDED/FAILED',
  `error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '安全错误',
  `expires_at` datetime(3) NOT NULL COMMENT '至少保留24小时并覆盖执行/补偿期；未完成不删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_api_idempotency_1`(`account_id` ASC, `scope` ASC, `request_id` ASC) USING BTREE,
  INDEX `idx_p_api_idempotency_1`(`expires_at` ASC) USING BTREE,
  CONSTRAINT `ck_p_api_idempotency_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_api_idempotency_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_api_idempotency_resource_id` CHECK (`resource_id` > 0),
  CONSTRAINT `ck_p_api_idempotency_status` CHECK (`status` in (_utf8mb4'PROCESSING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '管理API幂等结果' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_application
-- ----------------------------
DROP TABLE IF EXISTS `p_application`;
CREATE TABLE `p_application`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数 ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '所属开发者',
  `purpose` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'USER' COMMENT 'USER/VOICE_PREVIEW',
  `preview_voice_version_id` bigint NULL DEFAULT NULL COMMENT 'VOICE_PREVIEW candidate voice version',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '名称',
  `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '描述',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'ACTIVE/DISABLED/DELETING/DELETED',
  `auth_epoch` bigint NOT NULL DEFAULT 1 COMMENT '默认1；撤权/停用递增',
  `revision` bigint NOT NULL DEFAULT 1 COMMENT '默认1；配置发布乐观锁',
  `deleted_at` datetime(3) NULL DEFAULT NULL COMMENT '删除完成时间',
  `admin_disabled` tinyint NOT NULL DEFAULT 0 COMMENT 'Only platform administrators may change this flag',
  `avatar_id` bigint NULL DEFAULT NULL COMMENT 'Current logical Avatar',
  `voice_id` bigint NULL DEFAULT NULL COMMENT 'Current logical official Voice',
  `system_prompt` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL COMMENT 'Current developer System Prompt',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_p_application_1`(`account_id` ASC, `status` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_p_application_preview_voice`(`account_id` ASC, `purpose` ASC, `preview_voice_version_id` ASC) USING BTREE,
  INDEX `idx_p_application_avatar_id`(`avatar_id` ASC) USING BTREE,
  INDEX `idx_p_application_voice_id`(`voice_id` ASC) USING BTREE,
  CONSTRAINT `ck_p_application_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_application_admin_disabled` CHECK (`admin_disabled` in (0,1)),
  CONSTRAINT `ck_p_application_auth_epoch` CHECK (`auth_epoch` > 0),
  CONSTRAINT `ck_p_application_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_application_purpose` CHECK (`purpose` in (_utf8mb4'USER',_utf8mb4'VOICE_PREVIEW')),
  CONSTRAINT `ck_p_application_revision` CHECK (`revision` > 0),
  CONSTRAINT `ck_p_application_status` CHECK (`status` in (_utf8mb4'ACTIVE',_utf8mb4'DISABLED',_utf8mb4'DELETING',_utf8mb4'DELETED'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = 'Application与当前授权' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_application_skill
-- ----------------------------
DROP TABLE IF EXISTS `p_application_skill`;
CREATE TABLE `p_application_skill`  (
  `id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `account_id` bigint NOT NULL,
  `application_id` bigint NOT NULL,
  `skill_id` bigint NOT NULL,
  `sort_order` int NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_application_skill`(`application_id` ASC, `skill_id` ASC) USING BTREE,
  INDEX `idx_p_application_skill_account`(`account_id` ASC, `application_id` ASC, `sort_order` ASC) USING BTREE,
  INDEX `idx_p_application_skill_skill`(`skill_id` ASC) USING BTREE,
  CONSTRAINT `fk_p_application_skill_application` FOREIGN KEY (`application_id`) REFERENCES `p_application` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_p_application_skill_skill` FOREIGN KEY (`skill_id`) REFERENCES `p_skill` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_p_application_skill_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_application_skill_sort` CHECK (`sort_order` >= 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = 'Current logical Skill bindings for Application' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_avatar
-- ----------------------------
DROP TABLE IF EXISTS `p_avatar`;
CREATE TABLE `p_avatar`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数 ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '私有归属账号；官方资源的管理账号',
  `visibility` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'PRIVATE/OFFICIAL；不得仅以account_id为空判断公开',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '名称',
  `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '描述',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'DRAFT/PUBLISHED/UNLISTED/DISABLED/DELETING/DELETED',
  `current_version_id` bigint NULL DEFAULT NULL COMMENT '最新发布版本',
  `revision` bigint NOT NULL DEFAULT 1 COMMENT '默认1；乐观锁与实时状态修订',
  `deleted_at` datetime(3) NULL DEFAULT NULL COMMENT '删除完成时间',
  `cleanup_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PENDING/RUNNING/FAILED/COMPLETED',
  `cleanup_attempt_count` int NOT NULL DEFAULT 0,
  `next_cleanup_at` datetime(3) NULL DEFAULT NULL,
  `cleanup_lease_epoch` bigint NOT NULL DEFAULT 0,
  `cleanup_lease_owner` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL,
  `cleanup_lease_expires_at` datetime(3) NULL DEFAULT NULL,
  `last_cleanup_error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_p_avatar_1`(`account_id` ASC, `status` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_p_avatar_2`(`visibility` ASC, `status` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_p_avatar_cleanup`(`status` ASC, `cleanup_status` ASC, `next_cleanup_at` ASC) USING BTREE,
  CONSTRAINT `ck_p_avatar_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_avatar_current_version_id` CHECK (`current_version_id` > 0),
  CONSTRAINT `ck_p_avatar_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_avatar_revision` CHECK (`revision` > 0),
  CONSTRAINT `ck_p_avatar_status` CHECK (`status` in (_utf8mb4'DRAFT',_utf8mb4'PUBLISHED',_utf8mb4'UNLISTED',_utf8mb4'DISABLED',_utf8mb4'DELETING',_utf8mb4'DELETED')),
  CONSTRAINT `ck_p_avatar_visibility` CHECK (`visibility` in (_utf8mb4'PRIVATE',_utf8mb4'OFFICIAL'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = 'Avatar主表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_avatar_action
-- ----------------------------
DROP TABLE IF EXISTS `p_avatar_action`;
CREATE TABLE `p_avatar_action`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数 ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT 'Avatar管理账号',
  `avatar_version_id` bigint NOT NULL COMMENT 'p_avatar_version.id',
  `action_code` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '八项固定枚举',
  `atlas_file_id` bigint NOT NULL COMMENT '动作图集对象',
  `preview_file_id` bigint NULL DEFAULT NULL COMMENT '动作预览',
  `frame_count` int NOT NULL COMMENT '正整数；不写死为6',
  `fps` decimal(6, 2) NOT NULL COMMENT '正数；实际验证规格',
  `loop_enabled` tinyint NOT NULL DEFAULT 0 COMMENT '默认0；是否循环',
  `frame_layout` json NOT NULL COMMENT '帧坐标/时长/布局；不放图像二进制',
  `content_hash` binary(32) NOT NULL COMMENT '图集或动作manifest摘要',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_avatar_action_1`(`avatar_version_id` ASC, `action_code` ASC) USING BTREE,
  INDEX `idx_p_avatar_action_1`(`account_id` ASC, `avatar_version_id` ASC) USING BTREE,
  CONSTRAINT `fk_p_avatar_action_avatar_version_id` FOREIGN KEY (`avatar_version_id`) REFERENCES `p_avatar_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_p_avatar_action_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_avatar_action_action_code` CHECK (`action_code` in (_utf8mb4'idle',_utf8mb4'speaking',_utf8mb4'listening',_utf8mb4'thinking',_utf8mb4'nod',_utf8mb4'shake_head',_utf8mb4'wave',_utf8mb4'happy')),
  CONSTRAINT `ck_p_avatar_action_atlas_file_id` CHECK (`atlas_file_id` > 0),
  CONSTRAINT `ck_p_avatar_action_avatar_version_id` CHECK (`avatar_version_id` > 0),
  CONSTRAINT `ck_p_avatar_action_fps` CHECK (`fps` > 0),
  CONSTRAINT `ck_p_avatar_action_frame_count` CHECK (`frame_count` > 0),
  CONSTRAINT `ck_p_avatar_action_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_avatar_action_loop_enabled` CHECK (`loop_enabled` in (0,1)),
  CONSTRAINT `ck_p_avatar_action_preview_file_id` CHECK (`preview_file_id` > 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '版本内动作' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_avatar_action_result
-- ----------------------------
DROP TABLE IF EXISTS `p_avatar_action_result`;
CREATE TABLE `p_avatar_action_result`  (
  `id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `account_id` bigint NOT NULL,
  `avatar_version_id` bigint NOT NULL,
  `action_code` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `step_id` bigint NULL DEFAULT NULL,
  `attempt_id` bigint NULL DEFAULT NULL,
  `atlas_file_id` bigint NOT NULL,
  `manifest_file_id` bigint NOT NULL,
  `frame_count` int NOT NULL,
  `fps` decimal(6, 2) NOT NULL,
  `loop_enabled` tinyint NOT NULL,
  `frame_layout` json NOT NULL,
  `qa_report` json NOT NULL,
  `content_hash` binary(32) NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_avatar_result_attempt`(`attempt_id` ASC) USING BTREE,
  INDEX `idx_avatar_result_owner`(`account_id` ASC, `avatar_version_id` ASC, `action_code` ASC) USING BTREE,
  INDEX `fk_avatar_result_version`(`avatar_version_id` ASC) USING BTREE,
  INDEX `fk_avatar_result_atlas`(`atlas_file_id` ASC) USING BTREE,
  INDEX `fk_avatar_result_manifest`(`manifest_file_id` ASC) USING BTREE,
  CONSTRAINT `fk_avatar_result_atlas` FOREIGN KEY (`atlas_file_id`) REFERENCES `p_file` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_avatar_result_manifest` FOREIGN KEY (`manifest_file_id`) REFERENCES `p_file` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_avatar_result_version` FOREIGN KEY (`avatar_version_id`) REFERENCES `p_avatar_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_avatar_result_action` CHECK (`action_code` in (_utf8mb4'idle',_utf8mb4'speaking',_utf8mb4'listening',_utf8mb4'thinking',_utf8mb4'nod',_utf8mb4'shake_head',_utf8mb4'wave',_utf8mb4'happy')),
  CONSTRAINT `ck_avatar_result_geometry` CHECK ((`frame_count` > 0) and (`fps` > 0) and (`loop_enabled` in (0,1)))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_avatar_action_selection
-- ----------------------------
DROP TABLE IF EXISTS `p_avatar_action_selection`;
CREATE TABLE `p_avatar_action_selection`  (
  `avatar_version_id` bigint NOT NULL,
  `action_code` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `account_id` bigint NOT NULL,
  `revision` bigint NOT NULL DEFAULT 0,
  `selected_result_id` bigint NULL DEFAULT NULL,
  `accepted_result_id` bigint NULL DEFAULT NULL,
  `latest_attempt_id` bigint NULL DEFAULT NULL,
  `selection_closed` tinyint NOT NULL DEFAULT 0,
  `accepted_by` bigint NULL DEFAULT NULL,
  `accepted_at` datetime(3) NULL DEFAULT NULL,
  `updated_at` datetime(3) NOT NULL,
  PRIMARY KEY (`avatar_version_id`, `action_code`) USING BTREE,
  INDEX `idx_avatar_selection_owner`(`account_id` ASC, `avatar_version_id` ASC) USING BTREE,
  INDEX `fk_avatar_selection_result`(`selected_result_id` ASC) USING BTREE,
  INDEX `fk_avatar_selection_accepted`(`accepted_result_id` ASC) USING BTREE,
  CONSTRAINT `fk_avatar_selection_accepted` FOREIGN KEY (`accepted_result_id`) REFERENCES `p_avatar_action_result` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_avatar_selection_result` FOREIGN KEY (`selected_result_id`) REFERENCES `p_avatar_action_result` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_avatar_selection_version` FOREIGN KEY (`avatar_version_id`) REFERENCES `p_avatar_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_avatar_selection_accept` CHECK ((`accepted_result_id` is null) or ((`selected_result_id` is not null) and (`accepted_result_id` = `selected_result_id`))),
  CONSTRAINT `ck_avatar_selection_action` CHECK (`action_code` in (_utf8mb4'idle',_utf8mb4'speaking',_utf8mb4'listening',_utf8mb4'thinking',_utf8mb4'nod',_utf8mb4'shake_head',_utf8mb4'wave',_utf8mb4'happy')),
  CONSTRAINT `ck_avatar_selection_closed` CHECK (`selection_closed` in (0,1)),
  CONSTRAINT `ck_avatar_selection_revision` CHECK (`revision` >= 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_avatar_production_operation
-- ----------------------------
DROP TABLE IF EXISTS `p_avatar_production_operation`;
CREATE TABLE `p_avatar_production_operation`  (
  `account_id` bigint NOT NULL,
  `avatar_version_id` bigint NOT NULL,
  `action_code` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT '',
  `operation_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `request_hash` binary(32) NOT NULL,
  `response_json` json NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`account_id`, `avatar_version_id`, `action_code`, `operation_code`, `request_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_avatar_review
-- ----------------------------
DROP TABLE IF EXISTS `p_avatar_review`;
CREATE TABLE `p_avatar_review`  (
  `id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `account_id` bigint NOT NULL,
  `avatar_id` bigint NOT NULL,
  `avatar_version_id` bigint NOT NULL,
  `visual_accepted` tinyint NOT NULL,
  `review_note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_avatar_review_version`(`account_id` ASC, `avatar_version_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `fk_avatar_review_version`(`avatar_version_id` ASC) USING BTREE,
  CONSTRAINT `fk_avatar_review_version` FOREIGN KEY (`avatar_version_id`) REFERENCES `p_avatar_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_avatar_review_accepted` CHECK (`visual_accepted` in (0,1))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_avatar_version
-- ----------------------------
DROP TABLE IF EXISTS `p_avatar_version`;
CREATE TABLE `p_avatar_version`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数 ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT 'Avatar管理账号',
  `avatar_id` bigint NOT NULL COMMENT 'p_avatar.id',
  `version_no` int NOT NULL COMMENT '从1递增',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'BUILDING/REVIEW/PUBLISHED/REJECTED/DELETING/DELETED',
  `source_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'IMAGE/VIDEO/OFFICIAL_UPLOAD',
  `source_file_id` bigint NULL DEFAULT NULL COMMENT '原始文件，允许用户独立删除',
  `base_file_id` bigint NULL DEFAULT NULL COMMENT '生成基准图',
  `manifest_file_id` bigint NULL DEFAULT NULL COMMENT '包manifest',
  `preview_file_id` bigint NULL DEFAULT NULL COMMENT '预览图或动画',
  `frame_width` int NULL DEFAULT NULL COMMENT '单帧宽度，发布时必填',
  `frame_height` int NULL DEFAULT NULL COMMENT '单帧高度，发布时必填',
  `anchor_x` decimal(8, 6) NULL DEFAULT NULL COMMENT '归一化脚底锚点X，0到1',
  `anchor_y` decimal(8, 6) NULL DEFAULT NULL COMMENT '归一化脚底锚点Y，0到1',
  `manifest_schema` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '包协议版本',
  `pipeline_version` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '制作流水线版本',
  `generation_recipe` json NOT NULL COMMENT '非秘密模型/参数/提示词版本/参考哈希，长期保留',
  `capabilities` json NOT NULL COMMENT '实际动作及基础speaking声明',
  `qa_report` json NOT NULL COMMENT '结构化几何与人工检查结论，不存素材内容',
  `accepted_by` bigint NULL DEFAULT NULL COMMENT '验收者sys_user.user_id',
  `accepted_at` datetime(3) NULL DEFAULT NULL COMMENT '人工验收时间',
  `published_at` datetime(3) NULL DEFAULT NULL COMMENT '发布时间',
  `created_by` bigint NOT NULL COMMENT '版本创建者sys_user.user_id',
  `candidate_revision` bigint NOT NULL DEFAULT 0,
  `assembly_revision` bigint NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_avatar_version_1`(`avatar_id` ASC, `version_no` ASC) USING BTREE,
  INDEX `idx_p_avatar_version_1`(`account_id` ASC, `avatar_id` ASC, `status` ASC) USING BTREE,
  CONSTRAINT `fk_p_avatar_version_avatar_id` FOREIGN KEY (`avatar_id`) REFERENCES `p_avatar` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_avatar_candidate_revision` CHECK (`candidate_revision` >= 0),
  CONSTRAINT `ck_p_avatar_version_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_avatar_version_anchor_x` CHECK (`anchor_x` between 0 and 1),
  CONSTRAINT `ck_p_avatar_version_anchor_y` CHECK (`anchor_y` between 0 and 1),
  CONSTRAINT `ck_p_avatar_version_avatar_id` CHECK (`avatar_id` > 0),
  CONSTRAINT `ck_p_avatar_version_base_file_id` CHECK (`base_file_id` > 0),
  CONSTRAINT `ck_p_avatar_version_frame_height` CHECK (`frame_height` > 0),
  CONSTRAINT `ck_p_avatar_version_frame_width` CHECK (`frame_width` > 0),
  CONSTRAINT `ck_p_avatar_version_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_avatar_version_manifest_file_id` CHECK (`manifest_file_id` > 0),
  CONSTRAINT `ck_p_avatar_version_preview_file_id` CHECK (`preview_file_id` > 0),
  CONSTRAINT `ck_p_avatar_version_source_file_id` CHECK (`source_file_id` > 0),
  CONSTRAINT `ck_p_avatar_version_source_type` CHECK (`source_type` in (_utf8mb4'IMAGE',_utf8mb4'VIDEO',_utf8mb4'OFFICIAL_UPLOAD')),
  CONSTRAINT `ck_p_avatar_version_status` CHECK (`status` in (_utf8mb4'BUILDING',_utf8mb4'REVIEW',_utf8mb4'PUBLISHED',_utf8mb4'REJECTED',_utf8mb4'DELETING',_utf8mb4'DELETED')),
  CONSTRAINT `ck_p_avatar_version_version_no` CHECK (`version_no` > 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = 'Avatar不可变版本' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_avatar_version_operation
-- ----------------------------
DROP TABLE IF EXISTS `p_avatar_version_operation`;
CREATE TABLE `p_avatar_version_operation`  (
  `account_id` bigint NOT NULL,
  `avatar_id` bigint NOT NULL,
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `request_hash` binary(32) NOT NULL,
  `response_json` json NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`account_id`, `avatar_id`, `request_id`) USING BTREE,
  INDEX `fk_avatar_version_operation_avatar`(`avatar_id` ASC) USING BTREE,
  CONSTRAINT `fk_avatar_version_operation_avatar` FOREIGN KEY (`avatar_id`) REFERENCES `p_avatar` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_call_record
-- ----------------------------
DROP TABLE IF EXISTS `p_call_record`;
CREATE TABLE `p_call_record`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '归属账号',
  `application_id` bigint NULL DEFAULT NULL COMMENT '关联应用',
  `session_id` bigint NULL DEFAULT NULL COMMENT '跨库定位标识，不参与外键',
  `turn_id` bigint NULL DEFAULT NULL COMMENT '跨库轮次定位',
  `operation_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'attempt或运行调用的全局业务标识',
  `capability` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'GENERATION/LLM/ASR/TTS/TOOL/CONTEXT',
  `billing_owner` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'PLATFORM/DEVELOPER/NONE',
  `provider_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '厂商或转接标识',
  `model_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '模型',
  `provider_request_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '上游请求ID',
  `status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'STARTED/SUCCEEDED/FAILED/UNKNOWN/CANCELLED',
  `input_tokens` bigint NULL DEFAULT NULL COMMENT '未提供为NULL',
  `output_tokens` bigint NULL DEFAULT NULL COMMENT '未提供为NULL',
  `input_chars` bigint NULL DEFAULT NULL COMMENT '输入字符数',
  `audio_duration_ms` bigint NULL DEFAULT NULL COMMENT '音频时长',
  `image_count` int NULL DEFAULT NULL COMMENT '图片数',
  `usage_available` tinyint NOT NULL DEFAULT 0 COMMENT '默认0',
  `cost_amount` decimal(18, 6) NULL DEFAULT NULL COMMENT '厂商成本；未知为NULL',
  `currency` char(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '例如CNY',
  `cost_source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'UNKNOWN/PROVIDER/CONSOLE/ESTIMATED',
  `latency_ms` bigint NULL DEFAULT NULL COMMENT '总耗时',
  `first_byte_ms` bigint NULL DEFAULT NULL COMMENT '首字/首包耗时，按capability解释',
  `error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '安全错误码',
  `finished_at` datetime(3) NULL DEFAULT NULL COMMENT '完成时间',
  `expires_at` datetime(3) NULL DEFAULT NULL COMMENT '最终完成30天后清理',
  `quota_reservation_id` bigint NULL DEFAULT NULL COMMENT '官方生成/语音预占ID；Session删除后仍可核对结算',
  `fact_kind` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'LOGICAL',
  `voice_task_id` bigint NULL DEFAULT NULL,
  `voice_attempt_id` bigint NULL DEFAULT NULL,
  `voice_service_id` bigint NULL DEFAULT NULL,
  `logical_operation_key` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_call_record_1`(`operation_key` ASC) USING BTREE,
  INDEX `idx_p_call_record_1`(`account_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_p_call_record_2`(`account_id` ASC, `application_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_p_call_record_3`(`status` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_p_call_record_4`(`expires_at` ASC) USING BTREE,
  INDEX `idx_call_voice_task`(`account_id` ASC, `voice_task_id` ASC, `id` ASC) USING BTREE,
  CONSTRAINT `ck_call_fact_kind` CHECK (`fact_kind` in (_utf8mb4'LOGICAL',_utf8mb4'ATTEMPT')),
  CONSTRAINT `ck_p_call_record_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_call_record_application_id` CHECK (`application_id` > 0),
  CONSTRAINT `ck_p_call_record_audio_duration_ms` CHECK (`audio_duration_ms` >= 0),
  CONSTRAINT `ck_p_call_record_billing_owner` CHECK (`billing_owner` in (_utf8mb4'PLATFORM',_utf8mb4'DEVELOPER',_utf8mb4'NONE')),
  CONSTRAINT `ck_p_call_record_capability` CHECK (`capability` in (_utf8mb4'GENERATION',_utf8mb4'LLM',_utf8mb4'ASR',_utf8mb4'TTS',_utf8mb4'TOOL',_utf8mb4'CONTEXT')),
  CONSTRAINT `ck_p_call_record_cost_amount` CHECK (`cost_amount` >= 0),
  CONSTRAINT `ck_p_call_record_cost_source` CHECK (`cost_source` in (_utf8mb4'UNKNOWN',_utf8mb4'PROVIDER',_utf8mb4'CONSOLE',_utf8mb4'ESTIMATED',_utf8mb4'SELF_HOSTED',_utf8mb4'TEST')),
  CONSTRAINT `ck_p_call_record_first_byte_ms` CHECK (`first_byte_ms` >= 0),
  CONSTRAINT `ck_p_call_record_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_call_record_image_count` CHECK (`image_count` >= 0),
  CONSTRAINT `ck_p_call_record_input_chars` CHECK (`input_chars` >= 0),
  CONSTRAINT `ck_p_call_record_input_tokens` CHECK (`input_tokens` >= 0),
  CONSTRAINT `ck_p_call_record_latency_ms` CHECK (`latency_ms` >= 0),
  CONSTRAINT `ck_p_call_record_output_tokens` CHECK (`output_tokens` >= 0),
  CONSTRAINT `ck_p_call_record_quota_reservation_id` CHECK (`quota_reservation_id` > 0),
  CONSTRAINT `ck_p_call_record_session_id` CHECK (`session_id` > 0),
  CONSTRAINT `ck_p_call_record_status` CHECK (`status` in (_utf8mb4'STARTED',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED',_utf8mb4'UNKNOWN',_utf8mb4'CANCELLED')),
  CONSTRAINT `ck_p_call_record_turn_id` CHECK (`turn_id` > 0),
  CONSTRAINT `ck_p_call_record_usage_available` CHECK (`usage_available` in (0,1))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '调用用量及厂商成本' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_call_review
-- ----------------------------
DROP TABLE IF EXISTS `p_call_review`;
CREATE TABLE `p_call_review`  (
  `id` bigint NOT NULL,
  `call_id` bigint NOT NULL,
  `actor_id` bigint NOT NULL,
  `reviewed_status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `evidence_note` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `cost_amount` decimal(18, 6) NULL DEFAULT NULL,
  `currency` char(3) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL,
  `cost_source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_p_call_review_1`(`call_id` ASC, `created_at` ASC) USING BTREE,
  CONSTRAINT `ck_p_call_review_cost_amount` CHECK (`cost_amount` >= 0),
  CONSTRAINT `ck_p_call_review_cost_source` CHECK (`cost_source` in (_utf8mb4'CONSOLE',_utf8mb4'ESTIMATED')),
  CONSTRAINT `ck_p_call_review_status` CHECK (`reviewed_status` in (_utf8mb4'SUCCEEDED',_utf8mb4'FAILED',_utf8mb4'CANCELLED'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '管理员调用事实核对审计' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_email_token
-- ----------------------------
DROP TABLE IF EXISTS `p_email_token`;
CREATE TABLE `p_email_token`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数 ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `user_id` bigint NULL DEFAULT NULL COMMENT '关联sys_user.user_id；注册前允许为空',
  `email` varchar(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '规范化邮箱',
  `purpose` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'VERIFY_EMAIL/RESET_PASSWORD',
  `token_hash` binary(32) NOT NULL COMMENT '一次性随机令牌摘要，不存明文验证码',
  `expires_at` datetime(3) NOT NULL COMMENT '到期时间',
  `consumed_at` datetime(3) NULL DEFAULT NULL COMMENT '成功消费时间',
  `failed_attempts` int NOT NULL DEFAULT 0 COMMENT '默认0；失败尝试次数',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_email_token_1`(`token_hash` ASC) USING BTREE,
  INDEX `idx_p_email_token_1`(`email` ASC, `purpose` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_p_email_token_2`(`expires_at` ASC) USING BTREE,
  CONSTRAINT `ck_p_email_token_failed_attempts` CHECK (`failed_attempts` >= 0),
  CONSTRAINT `ck_p_email_token_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_email_token_purpose` CHECK (`purpose` in (_utf8mb4'VERIFY_EMAIL',_utf8mb4'RESET_PASSWORD')),
  CONSTRAINT `ck_p_email_token_user_id` CHECK (`user_id` > 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '邮箱验证与密码重置' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_file
-- ----------------------------
DROP TABLE IF EXISTS `p_file`;
CREATE TABLE `p_file`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数 ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '所属账号',
  `purpose` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'AVATAR_SOURCE/BASE/ATLAS/MANIFEST/PREVIEW/VOICE_SAMPLE/ACCOUNT_ICON（数据库设计§4.2补充）',
  `storage_provider` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '云存储适配器',
  `bucket` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '桶标识',
  `object_key` varchar(768) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '平台生成的ASCII对象键，不是签名URL',
  `original_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '原始文件名',
  `content_type` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '实测媒体类型',
  `size_bytes` bigint NOT NULL COMMENT '文件字节数',
  `sha256` binary(32) NULL DEFAULT NULL COMMENT '上传完成核验摘要',
  `width` int NULL DEFAULT NULL COMMENT '图片宽度',
  `height` int NULL DEFAULT NULL COMMENT '图片高度',
  `duration_ms` bigint NULL DEFAULT NULL COMMENT '音视频时长',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'UPLOADING/AVAILABLE/DELETE_PENDING/DELETED/FAILED',
  `storage_reservation_id` bigint NULL DEFAULT NULL COMMENT 'p_quota_reservation.id',
  `upload_expires_at` datetime(3) NULL DEFAULT NULL COMMENT '未完成上传回收时间',
  `delete_after` datetime(3) NULL DEFAULT NULL COMMENT '下次删除扫描时间',
  `rights_notice_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '真人素材弹窗版本',
  `rights_confirmed_at` datetime(3) NULL DEFAULT NULL COMMENT '上传者确认时间',
  `confirmed_by` bigint NULL DEFAULT NULL COMMENT '确认账号',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_file_1`(`storage_provider` ASC, `bucket` ASC, `object_key` ASC) USING BTREE,
  INDEX `idx_p_file_1`(`account_id` ASC, `status` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_p_file_2`(`status` ASC, `delete_after` ASC) USING BTREE,
  INDEX `idx_p_file_3`(`status` ASC, `upload_expires_at` ASC) USING BTREE,
  CONSTRAINT `ck_p_file_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_file_duration_ms` CHECK (`duration_ms` >= 0),
  CONSTRAINT `ck_p_file_height` CHECK (`height` > 0),
  CONSTRAINT `ck_p_file_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_file_purpose` CHECK (`purpose` in (_utf8mb4'AVATAR_SOURCE',_utf8mb4'BASE',_utf8mb4'ATLAS',_utf8mb4'MANIFEST',_utf8mb4'PREVIEW',_utf8mb4'VOICE_SAMPLE',_utf8mb4'ACCOUNT_ICON')),
  CONSTRAINT `ck_p_file_size_bytes` CHECK (`size_bytes` >= 0),
  CONSTRAINT `ck_p_file_status` CHECK (`status` in (_utf8mb4'UPLOADING',_utf8mb4'AVAILABLE',_utf8mb4'DELETE_PENDING',_utf8mb4'DELETED',_utf8mb4'FAILED')),
  CONSTRAINT `ck_p_file_storage_reservation_id` CHECK (`storage_reservation_id` > 0),
  CONSTRAINT `ck_p_file_width` CHECK (`width` > 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '长期资产与上传元数据' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_generation_attempt
-- ----------------------------
DROP TABLE IF EXISTS `p_generation_attempt`;
CREATE TABLE `p_generation_attempt`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数 ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '所属账号',
  `task_id` bigint NOT NULL COMMENT 'p_generation_task.id',
  `step_id` bigint NOT NULL COMMENT 'p_generation_step.id',
  `attempt_no` int NOT NULL COMMENT '步骤内递增',
  `lease_epoch` bigint NOT NULL COMMENT '执行租约代数',
  `provider_request_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '发出前持久生成，厂商支持时用于幂等',
  `provider_request_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '厂商返回请求ID',
  `provider_task_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '异步任务ID',
  `model_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '本次模型',
  `status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'PREPARED/SUBMITTED/UNKNOWN/SUCCEEDED/FAILED',
  `request_hash` binary(32) NOT NULL COMMENT '非秘密请求摘要',
  `output_file_id` bigint NULL DEFAULT NULL COMMENT '主要返回文件',
  `submitted_at` datetime(3) NULL DEFAULT NULL COMMENT '提交时间',
  `completed_at` datetime(3) NULL DEFAULT NULL COMMENT '完成时间',
  `reviewed_by` bigint NULL DEFAULT NULL COMMENT '人工核对人',
  `reviewed_at` datetime(3) NULL DEFAULT NULL COMMENT '核对时间',
  `review_note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '脱敏核对依据',
  `error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '安全错误码',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_generation_attempt_1`(`step_id` ASC, `attempt_no` ASC) USING BTREE,
  UNIQUE INDEX `uq_p_generation_attempt_2`(`provider_request_key` ASC) USING BTREE,
  INDEX `idx_p_generation_attempt_1`(`status` ASC, `submitted_at` ASC) USING BTREE,
  INDEX `idx_p_generation_attempt_2`(`account_id` ASC, `task_id` ASC) USING BTREE,
  CONSTRAINT `fk_p_generation_attempt_step_id` FOREIGN KEY (`step_id`) REFERENCES `p_generation_step` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_p_generation_attempt_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_generation_attempt_attempt_no` CHECK (`attempt_no` > 0),
  CONSTRAINT `ck_p_generation_attempt_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_generation_attempt_lease_epoch` CHECK (`lease_epoch` >= 0),
  CONSTRAINT `ck_p_generation_attempt_output_file_id` CHECK (`output_file_id` > 0),
  CONSTRAINT `ck_p_generation_attempt_status` CHECK (`status` in (_utf8mb4'PREPARED',_utf8mb4'SUBMITTED',_utf8mb4'UNKNOWN',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED')),
  CONSTRAINT `ck_p_generation_attempt_step_id` CHECK (`step_id` > 0),
  CONSTRAINT `ck_p_generation_attempt_task_id` CHECK (`task_id` > 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '实际API尝试与核对' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_generation_reconciliation_audit
-- ----------------------------
DROP TABLE IF EXISTS `p_generation_reconciliation_audit`;
CREATE TABLE `p_generation_reconciliation_audit`  (
  `id` bigint NOT NULL,
  `attempt_id` bigint NOT NULL,
  `task_id` bigint NOT NULL,
  `actor_id` bigint NOT NULL,
  `reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_p_generation_reconciliation_1`(`attempt_id` ASC, `created_at` ASC) USING BTREE,
  CONSTRAINT `ck_p_generation_reconciliation_status` CHECK (`status` in (_utf8mb4'COMPLETED',_utf8mb4'FAILED'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '管理员原任务核对审计' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_generation_step
-- ----------------------------
DROP TABLE IF EXISTS `p_generation_step`;
CREATE TABLE `p_generation_step`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数 ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '所属账号',
  `task_id` bigint NOT NULL COMMENT 'p_generation_task.id',
  `step_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'SOURCE/BASE/ACTION_idle等/PACKAGE/QA',
  `step_type` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'EXTRACT_REFERENCE/GENERATE_BASE/GENERATE_ACTION/PACKAGE/QA',
  `action_code` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '固定动作代码',
  `depends_on` json NOT NULL COMMENT '前置step_key数组；同任务内无环',
  `status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'WAITING/READY/RUNNING/POLLING/UNKNOWN/SUCCEEDED/FAILED',
  `stage_started_at` datetime(3) NOT NULL COMMENT '当前阶段开始时间，UTC',
  `attempt_no` int NOT NULL DEFAULT 0 COMMENT '默认0；已分配执行次数',
  `reserved_attempt_id` bigint NULL DEFAULT NULL,
  `result_file_id` bigint NULL DEFAULT NULL COMMENT '主要输出',
  `result_metadata` json NULL COMMENT '非秘密QA或产物引用',
  `lease_owner` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT 'Worker标识',
  `lease_epoch` bigint NOT NULL DEFAULT 0 COMMENT '默认0；每次领取递增',
  `lease_expires_at` datetime(3) NULL DEFAULT NULL COMMENT '租约截止',
  `heartbeat_at` datetime(3) NULL DEFAULT NULL COMMENT '最近心跳',
  `next_run_at` datetime(3) NULL DEFAULT NULL COMMENT '下一调度时间',
  `error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '安全错误码',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_generation_step_1`(`task_id` ASC, `step_key` ASC) USING BTREE,
  UNIQUE INDEX `uq_generation_step_reserved_attempt`(`reserved_attempt_id` ASC) USING BTREE,
  INDEX `idx_p_generation_step_1`(`status` ASC, `next_run_at` ASC) USING BTREE,
  INDEX `idx_p_generation_step_2`(`status` ASC, `lease_expires_at` ASC) USING BTREE,
  INDEX `idx_p_generation_step_3`(`account_id` ASC, `task_id` ASC) USING BTREE,
  CONSTRAINT `fk_p_generation_step_task_id` FOREIGN KEY (`task_id`) REFERENCES `p_generation_task` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_generation_step_reserved_attempt` CHECK ((`reserved_attempt_id` is null) or (`reserved_attempt_id` > 0)),
  CONSTRAINT `ck_p_generation_step_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_generation_step_action_code` CHECK (`action_code` in (_utf8mb4'idle',_utf8mb4'speaking',_utf8mb4'listening',_utf8mb4'thinking',_utf8mb4'nod',_utf8mb4'shake_head',_utf8mb4'wave',_utf8mb4'happy')),
  CONSTRAINT `ck_p_generation_step_attempt_no` CHECK (`attempt_no` >= 0),
  CONSTRAINT `ck_p_generation_step_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_generation_step_lease_epoch` CHECK (`lease_epoch` >= 0),
  CONSTRAINT `ck_p_generation_step_result_file_id` CHECK (`result_file_id` > 0),
  CONSTRAINT `ck_p_generation_step_status` CHECK (`status` in (_utf8mb4'WAITING',_utf8mb4'READY',_utf8mb4'RUNNING',_utf8mb4'POLLING',_utf8mb4'UNKNOWN',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED')),
  CONSTRAINT `ck_p_generation_step_step_type` CHECK (`step_type` in (_utf8mb4'EXTRACT_REFERENCE',_utf8mb4'GENERATE_BASE',_utf8mb4'GENERATE_ACTION',_utf8mb4'COMPLETE_CHARACTER',_utf8mb4'PACKAGE',_utf8mb4'QA')),
  CONSTRAINT `ck_p_generation_step_task_id` CHECK (`task_id` > 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '制作步骤及Worker租约' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_generation_task
-- ----------------------------
DROP TABLE IF EXISTS `p_generation_task`;
CREATE TABLE `p_generation_task`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数 ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '所属账号',
  `avatar_id` bigint NOT NULL COMMENT '目标Avatar',
  `avatar_version_id` bigint NOT NULL COMMENT '目标未发布版本',
  `source_file_id` bigint NULL DEFAULT NULL COMMENT '输入文件',
  `task_kind` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'GENERATE/REGENERATE',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'QUEUED/PROCESSING/SUCCEEDED/FAILED',
  `internal_state` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'READY/RUNNING/REVIEW_REQUIRED/FINISHED',
  `progress` smallint NOT NULL COMMENT '0到100',
  `official_service_id` bigint NOT NULL COMMENT '官方生成服务',
  `service_snapshot` json NOT NULL COMMENT '非秘密端点/模型/参数与secret_id引用',
  `pipeline_version` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '流水线版本',
  `quota_reservation_id` bigint NOT NULL COMMENT '一整套生成次数预占',
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '用户提交幂等ID',
  `request_hash` binary(32) NULL DEFAULT NULL,
  `retry_of_task_id` bigint NULL DEFAULT NULL COMMENT '失败任务恢复来源，非成功重生成',
  `error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '安全错误码',
  `error_message` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '脱敏说明',
  `started_at` datetime(3) NULL DEFAULT NULL COMMENT '开始时间',
  `finished_at` datetime(3) NULL DEFAULT NULL COMMENT '最终结束时间',
  `expires_at` datetime(3) NULL DEFAULT NULL COMMENT 'Deprecated; business task facts are retained',
  `reservation_no` int NOT NULL DEFAULT 1 COMMENT '默认1；用户显式重试已释放任务时递增',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_generation_task_1`(`account_id` ASC, `request_id` ASC) USING BTREE,
  INDEX `idx_p_generation_task_1`(`account_id` ASC, `status` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_p_generation_task_2`(`status` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_p_generation_task_3`(`expires_at` ASC) USING BTREE,
  INDEX `idx_generation_console_page`(`account_id` ASC, `created_at` ASC, `id` ASC) USING BTREE,
  CONSTRAINT `ck_p_generation_task_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_generation_task_avatar_id` CHECK (`avatar_id` > 0),
  CONSTRAINT `ck_p_generation_task_avatar_version_id` CHECK (`avatar_version_id` > 0),
  CONSTRAINT `ck_p_generation_task_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_generation_task_internal_state` CHECK (`internal_state` in (_utf8mb4'READY',_utf8mb4'RUNNING',_utf8mb4'REVIEW_REQUIRED',_utf8mb4'FINISHED')),
  CONSTRAINT `ck_p_generation_task_no_expiry` CHECK (`expires_at` is null),
  CONSTRAINT `ck_p_generation_task_official_service_id` CHECK (`official_service_id` > 0),
  CONSTRAINT `ck_p_generation_task_progress` CHECK (`progress` between 0 and 100),
  CONSTRAINT `ck_p_generation_task_quota_reservation_id` CHECK (`quota_reservation_id` > 0),
  CONSTRAINT `ck_p_generation_task_reservation_no` CHECK (`reservation_no` > 0),
  CONSTRAINT `ck_p_generation_task_retry_of_task_id` CHECK (`retry_of_task_id` > 0),
  CONSTRAINT `ck_p_generation_task_source_file_id` CHECK (`source_file_id` > 0),
  CONSTRAINT `ck_p_generation_task_status` CHECK (`status` in (_utf8mb4'QUEUED',_utf8mb4'PROCESSING',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED')),
  CONSTRAINT `ck_p_generation_task_task_kind` CHECK (`task_kind` in (_utf8mb4'GENERATE',_utf8mb4'REGENERATE'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '一次完整制作任务' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_inbox
-- ----------------------------
DROP TABLE IF EXISTS `p_inbox`;
CREATE TABLE `p_inbox`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '事件归属账号',
  `consumer_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '消费逻辑标识',
  `event_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '上游事件ID',
  `payload_hash` binary(32) NOT NULL COMMENT '防止同事件ID不同内容',
  `processed_at` datetime(3) NOT NULL COMMENT '本地业务事务完成时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_inbox_1`(`consumer_name` ASC, `event_id` ASC) USING BTREE,
  INDEX `idx_p_inbox_1`(`processed_at` ASC) USING BTREE,
  CONSTRAINT `ck_p_inbox_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_inbox_id` CHECK (`id` > 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '消费事件去重' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_job_lease
-- ----------------------------
DROP TABLE IF EXISTS `p_job_lease`;
CREATE TABLE `p_job_lease`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `job_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '清理/投递/补偿扫描任务标识',
  `lease_owner` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '当前执行实例',
  `lease_epoch` bigint NOT NULL DEFAULT 0 COMMENT '默认0；领取代数',
  `lease_expires_at` datetime(3) NULL DEFAULT NULL COMMENT '租约到期',
  `last_completed_at` datetime(3) NULL DEFAULT NULL COMMENT '上次完成',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_job_lease_1`(`job_key` ASC) USING BTREE,
  INDEX `idx_p_job_lease_1`(`lease_expires_at` ASC) USING BTREE,
  CONSTRAINT `ck_p_job_lease_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_job_lease_lease_epoch` CHECK (`lease_epoch` >= 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '持久扫描任务租约' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_migration_audit
-- ----------------------------
DROP TABLE IF EXISTS `p_migration_audit`;
CREATE TABLE `p_migration_audit`  (
  `migration_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `metric_key` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL,
  `metric_value` bigint NOT NULL,
  `recorded_at` datetime(3) NOT NULL,
  PRIMARY KEY (`migration_key`, `metric_key`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = 'Destructive migration pre/post counts' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_official_service
-- ----------------------------
DROP TABLE IF EXISTS `p_official_service`;
CREATE TABLE `p_official_service`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数 ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '管理员账号',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '服务显示名称',
  `capability` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'AVATAR_GENERATION/TTS',
  `provider_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '厂商适配器标识',
  `endpoint` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '固定官方API地址',
  `model_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '显式模型版本',
  `secret_id` bigint NOT NULL COMMENT 'p_secret.id',
  `parameters` json NOT NULL COMMENT '非秘密参数，按能力白名单校验',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'ACTIVE/DISABLED',
  `revision` bigint NOT NULL DEFAULT 1 COMMENT '默认1；服务配置修订号',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_p_official_service_1`(`capability` ASC, `status` ASC) USING BTREE,
  CONSTRAINT `ck_p_official_service_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_official_service_capability` CHECK (`capability` in (_utf8mb4'AVATAR_GENERATION',_utf8mb4'TTS',_utf8mb4'ASR')),
  CONSTRAINT `ck_p_official_service_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_official_service_revision` CHECK (`revision` > 0),
  CONSTRAINT `ck_p_official_service_secret_id` CHECK (`secret_id` > 0),
  CONSTRAINT `ck_p_official_service_status` CHECK (`status` in (_utf8mb4'ACTIVE',_utf8mb4'DISABLED'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '官方生成及TTS服务' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_outbox
-- ----------------------------
DROP TABLE IF EXISTS `p_outbox`;
CREATE TABLE `p_outbox`  (
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
  UNIQUE INDEX `uq_p_outbox_1`(`event_id` ASC) USING BTREE,
  INDEX `idx_p_outbox_1`(`status` ASC, `next_run_at` ASC) USING BTREE,
  INDEX `idx_p_outbox_2`(`status` ASC, `lease_expires_at` ASC) USING BTREE,
  CONSTRAINT `ck_p_outbox_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_outbox_attempt_count` CHECK (`attempt_count` >= 0),
  CONSTRAINT `ck_p_outbox_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_outbox_schema_version` CHECK (`schema_version` > 0),
  CONSTRAINT `ck_p_outbox_status` CHECK (`status` in (_utf8mb4'PENDING',_utf8mb4'SENDING',_utf8mb4'SENT'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '本地事务事件发件箱' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_point_balance
-- ----------------------------
DROP TABLE IF EXISTS `p_point_balance`;
CREATE TABLE `p_point_balance`  (
  `account_id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `granted_cent` bigint NOT NULL DEFAULT 0,
  `used_cent` bigint NOT NULL DEFAULT 0,
  `reserved_cent` bigint NOT NULL DEFAULT 0,
  `revision` bigint NOT NULL DEFAULT 1,
  PRIMARY KEY (`account_id`) USING BTREE,
  CONSTRAINT `ck_p_point_balance_values` CHECK ((`granted_cent` >= 0) and (`used_cent` >= 0) and (`reserved_cent` >= 0) and ((`used_cent` + `reserved_cent`) <= `granted_cent`))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Account point balance in cent units' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_point_entry
-- ----------------------------
DROP TABLE IF EXISTS `p_point_entry`;
CREATE TABLE `p_point_entry`  (
  `id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `account_id` bigint NOT NULL,
  `reservation_id` bigint NULL DEFAULT NULL,
  `event_key` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `entry_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `delta_granted_cent` bigint NOT NULL DEFAULT 0,
  `delta_used_cent` bigint NOT NULL DEFAULT 0,
  `delta_reserved_cent` bigint NOT NULL DEFAULT 0,
  `operator_id` bigint NULL DEFAULT NULL,
  `reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_point_entry_event`(`account_id` ASC, `event_key` ASC) USING BTREE,
  INDEX `idx_p_point_entry_account`(`account_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `fk_p_point_entry_reservation`(`reservation_id` ASC) USING BTREE,
  CONSTRAINT `fk_p_point_entry_reservation` FOREIGN KEY (`reservation_id`) REFERENCES `p_point_reservation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_p_point_entry_type` CHECK (`entry_type` in (_utf8mb4'GRANT',_utf8mb4'RESERVE',_utf8mb4'SETTLE',_utf8mb4'RELEASE'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Immutable point ledger' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_point_rate_version
-- ----------------------------
DROP TABLE IF EXISTS `p_point_rate_version`;
CREATE TABLE `p_point_rate_version`  (
  `id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `version_no` bigint NOT NULL,
  `generation_action_cent` bigint NOT NULL,
  `tts_character_cent` bigint NOT NULL,
  `storage_byte_cent` bigint NOT NULL,
  `effective_at` datetime(3) NOT NULL,
  `created_by` bigint NOT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_point_rate_version_no`(`version_no` ASC) USING BTREE,
  INDEX `idx_p_point_rate_effective`(`effective_at` ASC, `version_no` ASC) USING BTREE,
  CONSTRAINT `ck_p_point_rate_values` CHECK ((`generation_action_cent` >= 0) and (`tts_character_cent` >= 0) and (`storage_byte_cent` >= 0))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Published point rates; 100 cent units equal one point' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_point_reservation
-- ----------------------------
DROP TABLE IF EXISTS `p_point_reservation`;
CREATE TABLE `p_point_reservation`  (
  `id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `account_id` bigint NOT NULL,
  `business_type` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `business_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `reservation_no` int NOT NULL,
  `billing_item` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `measured_units` bigint NOT NULL,
  `unit_price_cent` bigint NOT NULL,
  `rate_version_id` bigint NOT NULL,
  `reserved_cent` bigint NOT NULL,
  `settled_cent` bigint NOT NULL DEFAULT 0,
  `state` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `settled_at` datetime(3) NULL DEFAULT NULL,
  `released_at` datetime(3) NULL DEFAULT NULL,
  `tts_request_key` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `tts_application_id` bigint NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_point_reservation_business`(`account_id` ASC, `business_type` ASC, `business_id` ASC, `reservation_no` ASC) USING BTREE,
  INDEX `idx_p_point_reservation_account`(`account_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `fk_p_point_reservation_rate`(`rate_version_id` ASC) USING BTREE,
  INDEX `idx_p_point_tts_request`(`account_id` ASC, `tts_request_key` ASC, `id` ASC) USING BTREE,
  CONSTRAINT `fk_p_point_reservation_rate` FOREIGN KEY (`rate_version_id`) REFERENCES `p_point_rate_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_p_point_reservation_item` CHECK (`billing_item` in (_utf8mb4'GENERATION_ACTION',_utf8mb4'TTS_CHARACTER')),
  CONSTRAINT `ck_p_point_reservation_state` CHECK (`state` in (_utf8mb4'RESERVED',_utf8mb4'SETTLED',_utf8mb4'RELEASED',_utf8mb4'REVIEW_REQUIRED')),
  CONSTRAINT `ck_p_point_reservation_values` CHECK ((`reservation_no` > 0) and (`measured_units` > 0) and (`unit_price_cent` >= 0) and (`reserved_cent` >= 0) and (`settled_cent` >= 0) and (`settled_cent` <= `reserved_cent`))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Price snapshots and point reservations' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_quota_balance
-- ----------------------------
DROP TABLE IF EXISTS `p_quota_balance`;
CREATE TABLE `p_quota_balance`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '所属账号',
  `quota_type` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'AVATAR_COUNT/TTS_CHAR/STORAGE_BYTE',
  `granted_units` bigint NOT NULL COMMENT '管理员累计授予额度',
  `used_units` bigint NOT NULL COMMENT '已用；存储表示当前已占用字节',
  `reserved_units` bigint NOT NULL COMMENT '正在预占',
  `revision` bigint NOT NULL DEFAULT 1 COMMENT '默认1；并发更新版本',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_quota_balance_1`(`account_id` ASC, `quota_type` ASC) USING BTREE,
  CONSTRAINT `ck_p_quota_balance_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_quota_balance_capacity` CHECK ((`used_units` <= `granted_units`) and (`reserved_units` <= (`granted_units` - `used_units`))),
  CONSTRAINT `ck_p_quota_balance_granted_units` CHECK (`granted_units` >= 0),
  CONSTRAINT `ck_p_quota_balance_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_quota_balance_quota_type` CHECK (`quota_type` in (_utf8mb4'AVATAR_COUNT',_utf8mb4'TTS_CHAR',_utf8mb4'STORAGE_BYTE')),
  CONSTRAINT `ck_p_quota_balance_reserved_units` CHECK (`reserved_units` >= 0),
  CONSTRAINT `ck_p_quota_balance_revision` CHECK (`revision` > 0),
  CONSTRAINT `ck_p_quota_balance_used_units` CHECK (`used_units` >= 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '账号分类额度余额' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_quota_entry
-- ----------------------------
DROP TABLE IF EXISTS `p_quota_entry`;
CREATE TABLE `p_quota_entry`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '所属账号',
  `quota_type` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '额度类别',
  `reservation_id` bigint NULL DEFAULT NULL COMMENT '预占ID；管理员调整可空',
  `event_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '业务结算/调整唯一标识',
  `entry_type` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'GRANT/RESERVE/SETTLE/RELEASE/STORAGE_FREE/ADJUST',
  `delta_granted` bigint NOT NULL COMMENT '授予额度变化量',
  `delta_used` bigint NOT NULL COMMENT '使用额度变化量',
  `delta_reserved` bigint NOT NULL COMMENT '预占变化量',
  `operator_id` bigint NULL DEFAULT NULL COMMENT '人工操作账号',
  `reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '调整依据',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_quota_entry_1`(`account_id` ASC, `event_key` ASC) USING BTREE,
  INDEX `idx_p_quota_entry_1`(`account_id` ASC, `quota_type` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_p_quota_entry_fk_reservation_id`(`reservation_id` ASC) USING BTREE,
  CONSTRAINT `fk_p_quota_entry_reservation_id` FOREIGN KEY (`reservation_id`) REFERENCES `p_quota_reservation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_p_quota_entry_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_quota_entry_entry_type` CHECK (`entry_type` in (_utf8mb4'GRANT',_utf8mb4'RESERVE',_utf8mb4'SETTLE',_utf8mb4'RELEASE',_utf8mb4'STORAGE_FREE',_utf8mb4'ADJUST')),
  CONSTRAINT `ck_p_quota_entry_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_quota_entry_operator_id` CHECK (`operator_id` > 0),
  CONSTRAINT `ck_p_quota_entry_quota_type` CHECK (`quota_type` in (_utf8mb4'AVATAR_COUNT',_utf8mb4'TTS_CHAR',_utf8mb4'STORAGE_BYTE')),
  CONSTRAINT `ck_p_quota_entry_reservation_id` CHECK (`reservation_id` > 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '额度不可变流水' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_quota_reservation
-- ----------------------------
DROP TABLE IF EXISTS `p_quota_reservation`;
CREATE TABLE `p_quota_reservation`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '所属账号',
  `quota_type` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'AVATAR_COUNT/TTS_CHAR/STORAGE_BYTE',
  `business_type` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'GENERATION/TTS_SEGMENT/UPLOAD',
  `business_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '任务ID、turn_id:segment_id或file_id',
  `reservation_no` int NOT NULL DEFAULT 1 COMMENT '默认1；失败释放后显式重试递增',
  `reserved_units` bigint NOT NULL COMMENT '本次预占整数',
  `settled_units` bigint NOT NULL DEFAULT 0 COMMENT '默认0；实际结算量',
  `state` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'RESERVED/SETTLED/RELEASED/REVIEW_REQUIRED',
  `expires_at` datetime(3) NULL DEFAULT NULL COMMENT '触发核对，不自动退款',
  `settled_at` datetime(3) NULL DEFAULT NULL COMMENT '最终结算时间',
  `released_at` datetime(3) NULL DEFAULT NULL COMMENT '最终释放时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_quota_reservation_1`(`account_id` ASC, `quota_type` ASC, `business_type` ASC, `business_id` ASC, `reservation_no` ASC) USING BTREE,
  INDEX `idx_p_quota_reservation_1`(`state` ASC, `expires_at` ASC) USING BTREE,
  INDEX `idx_p_quota_reservation_2`(`account_id` ASC, `created_at` ASC) USING BTREE,
  CONSTRAINT `ck_p_quota_reservation_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_quota_reservation_business_type` CHECK (`business_type` in (_utf8mb4'GENERATION',_utf8mb4'TTS_SEGMENT',_utf8mb4'UPLOAD')),
  CONSTRAINT `ck_p_quota_reservation_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_quota_reservation_quota_type` CHECK (`quota_type` in (_utf8mb4'AVATAR_COUNT',_utf8mb4'TTS_CHAR',_utf8mb4'STORAGE_BYTE')),
  CONSTRAINT `ck_p_quota_reservation_reservation_no` CHECK (`reservation_no` > 0),
  CONSTRAINT `ck_p_quota_reservation_reserved_units` CHECK (`reserved_units` >= 0),
  CONSTRAINT `ck_p_quota_reservation_settled_units` CHECK (`settled_units` >= 0),
  CONSTRAINT `ck_p_quota_reservation_state` CHECK (`state` in (_utf8mb4'RESERVED',_utf8mb4'SETTLED',_utf8mb4'RELEASED',_utf8mb4'REVIEW_REQUIRED'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '额度预占与结算' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_resource_reference
-- ----------------------------
DROP TABLE IF EXISTS `p_resource_reference`;
CREATE TABLE `p_resource_reference`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数 ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '引用者账号；可与官方资源管理账号不同',
  `holder_type` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'APP_CURRENT/SESSION/GENERATION',
  `holder_id` bigint NOT NULL COMMENT '应用/Session/任务ID',
  `operation_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '引用操作幂等标识',
  `resource_type` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'APP_CONFIG/AVATAR_VERSION/VOICE_VERSION/SKILL_VERSION/RELAY_VERSION',
  `resource_id` bigint NOT NULL COMMENT '对应资源版本ID',
  `state` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'RESERVED/CONFIRMED/RELEASED',
  `lease_expires_at` datetime(3) NULL DEFAULT NULL COMMENT '待核对时间，非自动释放依据',
  `confirmed_at` datetime(3) NULL DEFAULT NULL COMMENT '确认时间',
  `released_at` datetime(3) NULL DEFAULT NULL COMMENT '释放时间',
  `next_check_at` datetime(3) NULL DEFAULT NULL COMMENT '补偿核对时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_resource_reference_1`(`holder_type` ASC, `holder_id` ASC, `resource_type` ASC, `resource_id` ASC) USING BTREE,
  INDEX `idx_p_resource_reference_1`(`resource_type` ASC, `resource_id` ASC, `state` ASC) USING BTREE,
  INDEX `idx_p_resource_reference_2`(`state` ASC, `next_check_at` ASC) USING BTREE,
  INDEX `idx_p_resource_reference_3`(`account_id` ASC, `holder_type` ASC, `holder_id` ASC) USING BTREE,
  CONSTRAINT `ck_p_resource_reference_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_resource_reference_holder_id` CHECK (`holder_id` > 0),
  CONSTRAINT `ck_p_resource_reference_holder_type` CHECK (`holder_type` in (_utf8mb4'APP_CURRENT',_utf8mb4'SESSION',_utf8mb4'GENERATION',_utf8mb4'VOICE_VERSION')),
  CONSTRAINT `ck_p_resource_reference_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_resource_reference_resource_id` CHECK (`resource_id` > 0),
  CONSTRAINT `ck_p_resource_reference_resource_type` CHECK (`resource_type` in (_utf8mb4'APPLICATION_SNAPSHOT',_utf8mb4'AVATAR_VERSION',_utf8mb4'VOICE_VERSION',_utf8mb4'SKILL',_utf8mb4'VOICE_REFERENCE')),
  CONSTRAINT `ck_p_resource_reference_state` CHECK (`state` in (_utf8mb4'RESERVED',_utf8mb4'CONFIRMED',_utf8mb4'RELEASED'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '资产绑定与跨库引用预留' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_secret
-- ----------------------------
DROP TABLE IF EXISTS `p_secret`;
CREATE TABLE `p_secret`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数 ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '管理该秘密的账号；官方秘密由管理员账号管理',
  `purpose` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'OFFICIAL_PROVIDER/RELAY_ACCESS/TOOL_ACCESS/WEBHOOK_SIGN',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '显示名称',
  `ciphertext` mediumblob NOT NULL COMMENT '经审核加密库输出的密文',
  `nonce` varbinary(32) NOT NULL COMMENT '随机nonce/IV',
  `auth_tag` varbinary(32) NOT NULL COMMENT 'AEAD认证标签',
  `key_version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '部署主密钥版本',
  `algorithm` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '受支持算法标识',
  `display_suffix` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '脱敏后缀',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'ACTIVE/DISABLED/DELETED',
  `revision` bigint NOT NULL DEFAULT 1 COMMENT '默认1；原子轮换序号',
  `expires_at` datetime(3) NULL DEFAULT NULL COMMENT '有效期',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_p_secret_1`(`account_id` ASC, `purpose` ASC, `status` ASC) USING BTREE,
  CONSTRAINT `ck_p_secret_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_secret_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_secret_purpose` CHECK (`purpose` in (_utf8mb4'OFFICIAL_PROVIDER',_utf8mb4'TOOL_ACCESS')),
  CONSTRAINT `ck_p_secret_revision` CHECK (`revision` > 0),
  CONSTRAINT `ck_p_secret_status` CHECK (`status` in (_utf8mb4'ACTIVE',_utf8mb4'DISABLED',_utf8mb4'DELETED'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '平台需可解密的秘密' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_skill
-- ----------------------------
DROP TABLE IF EXISTS `p_skill`;
CREATE TABLE `p_skill`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数 ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '私有归属账号；官方资源的管理账号',
  `visibility` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'PRIVATE/OFFICIAL；不得仅以account_id为空判断公开',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '名称',
  `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '描述',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'DRAFT/PUBLISHED/UNLISTED/DISABLED/DELETING/DELETED',
  `revision` bigint NOT NULL DEFAULT 1 COMMENT '默认1；乐观锁与实时状态修订',
  `deleted_at` datetime(3) NULL DEFAULT NULL COMMENT '删除完成时间',
  `skill_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL,
  `tool_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL,
  `instructions` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL,
  `context_requirements` json NULL,
  `tool_url` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL,
  `http_method` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL,
  `input_schema` json NULL,
  `output_schema` json NULL,
  `tool_secret_id` bigint NULL DEFAULT NULL,
  `requires_user_credential` tinyint NOT NULL DEFAULT 0,
  `identity_binding` json NULL,
  `frontend_fields` json NULL,
  `timeout_ms` int NULL DEFAULT NULL,
  `max_result_bytes` bigint NULL DEFAULT NULL,
  `max_calls_per_session` int NULL DEFAULT NULL,
  `import_format` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL,
  `content_hash` binary(32) NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_p_skill_1`(`account_id` ASC, `status` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_p_skill_2`(`visibility` ASC, `status` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_p_skill_tool_name`(`tool_name` ASC) USING BTREE,
  CONSTRAINT `ck_p_skill_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_skill_current_calls` CHECK (`max_calls_per_session` between 1 and 100),
  CONSTRAINT `ck_p_skill_current_method` CHECK (`http_method` in (_utf8mb4'GET',_utf8mb4'POST')),
  CONSTRAINT `ck_p_skill_current_type` CHECK (`skill_type` in (_utf8mb4'PROMPT',_utf8mb4'HTTP_TOOL')),
  CONSTRAINT `ck_p_skill_current_user_credential` CHECK (`requires_user_credential` in (0,1)),
  CONSTRAINT `ck_p_skill_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_skill_revision` CHECK (`revision` > 0),
  CONSTRAINT `ck_p_skill_status` CHECK (`status` in (_utf8mb4'DRAFT',_utf8mb4'PUBLISHED',_utf8mb4'UNLISTED',_utf8mb4'DISABLED',_utf8mb4'DELETING',_utf8mb4'DELETED')),
  CONSTRAINT `ck_p_skill_visibility` CHECK (`visibility` in (_utf8mb4'PRIVATE',_utf8mb4'OFFICIAL'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = 'Skill主表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_usage_daily
-- ----------------------------
DROP TABLE IF EXISTS `p_usage_daily`;
CREATE TABLE `p_usage_daily`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '归属账号',
  `application_scope_id` bigint NOT NULL COMMENT '应用ID，无应用调用用0，避免NULL唯一键漏洞',
  `usage_date` date NOT NULL COMMENT 'UTC统计日，界面可换时区',
  `capability` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '能力类别',
  `billing_owner` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'PLATFORM/DEVELOPER/NONE',
  `request_count` bigint NOT NULL DEFAULT 0 COMMENT '默认0',
  `success_count` bigint NOT NULL DEFAULT 0 COMMENT '默认0',
  `failure_count` bigint NOT NULL DEFAULT 0 COMMENT '默认0',
  `unknown_count` bigint NOT NULL DEFAULT 0 COMMENT '默认0',
  `input_tokens` bigint NOT NULL DEFAULT 0 COMMENT '默认0；仅已报告数值累计',
  `output_tokens` bigint NOT NULL DEFAULT 0 COMMENT '默认0',
  `input_chars` bigint NOT NULL DEFAULT 0 COMMENT '默认0',
  `image_count` bigint NOT NULL DEFAULT 0 COMMENT '默认0',
  `audio_duration_ms` bigint NOT NULL DEFAULT 0 COMMENT '默认0',
  `known_usage_count` bigint NOT NULL DEFAULT 0 COMMENT '默认0；避免缺失被误读为0',
  `known_cost_count` bigint NOT NULL DEFAULT 0 COMMENT '默认0',
  `cost_amount` decimal(18, 6) NOT NULL DEFAULT 0.000000 COMMENT '默认0；只汇总同币种已知成本',
  `currency` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'CNY等；未知用UNKNOWN',
  `expires_at` datetime(3) NULL DEFAULT NULL COMMENT 'No automatic deletion',
  `cancelled_count` bigint NOT NULL DEFAULT 0 COMMENT '默认0；取消调用数',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_usage_daily_1`(`account_id` ASC, `application_scope_id` ASC, `usage_date` ASC, `capability` ASC, `billing_owner` ASC, `currency` ASC) USING BTREE,
  INDEX `idx_p_usage_daily_1`(`account_id` ASC, `usage_date` ASC) USING BTREE,
  INDEX `idx_p_usage_daily_2`(`expires_at` ASC) USING BTREE,
  CONSTRAINT `ck_p_usage_daily_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_usage_daily_application_scope_id` CHECK (`application_scope_id` >= 0),
  CONSTRAINT `ck_p_usage_daily_audio_duration_ms` CHECK (`audio_duration_ms` >= 0),
  CONSTRAINT `ck_p_usage_daily_billing_owner` CHECK (`billing_owner` in (_utf8mb4'PLATFORM',_utf8mb4'DEVELOPER',_utf8mb4'NONE')),
  CONSTRAINT `ck_p_usage_daily_cancelled_count` CHECK (`cancelled_count` >= 0),
  CONSTRAINT `ck_p_usage_daily_capability` CHECK (`capability` in (_utf8mb4'GENERATION',_utf8mb4'LLM',_utf8mb4'ASR',_utf8mb4'TTS',_utf8mb4'TOOL',_utf8mb4'CONTEXT')),
  CONSTRAINT `ck_p_usage_daily_cost_amount` CHECK (`cost_amount` >= 0),
  CONSTRAINT `ck_p_usage_daily_failure_count` CHECK (`failure_count` >= 0),
  CONSTRAINT `ck_p_usage_daily_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_usage_daily_image_count` CHECK (`image_count` >= 0),
  CONSTRAINT `ck_p_usage_daily_input_chars` CHECK (`input_chars` >= 0),
  CONSTRAINT `ck_p_usage_daily_input_tokens` CHECK (`input_tokens` >= 0),
  CONSTRAINT `ck_p_usage_daily_known_cost_count` CHECK (`known_cost_count` >= 0),
  CONSTRAINT `ck_p_usage_daily_known_usage_count` CHECK (`known_usage_count` >= 0),
  CONSTRAINT `ck_p_usage_daily_output_tokens` CHECK (`output_tokens` >= 0),
  CONSTRAINT `ck_p_usage_daily_request_count` CHECK (`request_count` >= 0),
  CONSTRAINT `ck_p_usage_daily_success_count` CHECK (`success_count` >= 0),
  CONSTRAINT `ck_p_usage_daily_unknown_count` CHECK (`unknown_count` >= 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = '每日汇总用量' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_voice
-- ----------------------------
DROP TABLE IF EXISTS `p_voice`;
CREATE TABLE `p_voice`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数 ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT '私有归属账号；官方资源的管理账号',
  `visibility` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'PRIVATE/OFFICIAL；不得仅以account_id为空判断公开',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '名称',
  `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '描述',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'DRAFT/PUBLISHED/UNLISTED/DISABLED/DELETING/DELETED',
  `current_version_id` bigint NULL DEFAULT NULL COMMENT '最新发布版本',
  `revision` bigint NOT NULL DEFAULT 1 COMMENT '默认1；乐观锁与实时状态修订',
  `deleted_at` datetime(3) NULL DEFAULT NULL COMMENT '删除完成时间',
  `cleanup_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'NONE' COMMENT 'NONE/PENDING/RUNNING/FAILED/COMPLETED',
  `cleanup_attempt_count` int NOT NULL DEFAULT 0,
  `next_cleanup_at` datetime(3) NULL DEFAULT NULL,
  `cleanup_lease_epoch` bigint NOT NULL DEFAULT 0,
  `cleanup_lease_owner` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL,
  `cleanup_lease_expires_at` datetime(3) NULL DEFAULT NULL,
  `last_cleanup_error_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_p_voice_1`(`account_id` ASC, `status` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_p_voice_2`(`visibility` ASC, `status` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_p_voice_cleanup`(`status` ASC, `cleanup_status` ASC, `next_cleanup_at` ASC) USING BTREE,
  CONSTRAINT `ck_p_voice_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_voice_current_version_id` CHECK (`current_version_id` > 0),
  CONSTRAINT `ck_p_voice_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_voice_revision` CHECK (`revision` > 0),
  CONSTRAINT `ck_p_voice_status` CHECK (`status` in (_utf8mb4'DRAFT',_utf8mb4'PUBLISHED',_utf8mb4'UNLISTED',_utf8mb4'DISABLED',_utf8mb4'DELETING',_utf8mb4'DELETED')),
  CONSTRAINT `ck_p_voice_visibility` CHECK (`visibility` in (_utf8mb4'PRIVATE',_utf8mb4'OFFICIAL'))
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = 'Voice主表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for p_voice_version
-- ----------------------------
DROP TABLE IF EXISTS `p_voice_version`;
CREATE TABLE `p_voice_version`  (
  `id` bigint NOT NULL COMMENT '主键，服务生成的正整数 ID',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间，UTC',
  `updated_at` datetime(3) NOT NULL COMMENT '更新时间，UTC',
  `account_id` bigint NOT NULL COMMENT 'Voice管理账号',
  `voice_id` bigint NOT NULL COMMENT 'p_voice.id',
  `version_no` int NOT NULL COMMENT '从1递增',
  `service_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT 'OFFICIAL/RELAY',
  `official_service_id` bigint NULL DEFAULT NULL COMMENT '官方TTS服务',
  `voice_code` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL COMMENT '厂商或转接音色别名',
  `language_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '语言标识',
  `model_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL COMMENT '可选显式模型',
  `parameters` json NOT NULL COMMENT '音速等白名单非秘密参数',
  `official_config_snapshot` json NULL COMMENT '官方服务非秘密配置，含secret_id引用',
  `sample_file_id` bigint NULL DEFAULT NULL COMMENT '官方/自有音色演示音频，不是会话临时音频',
  `created_by` bigint NOT NULL COMMENT '版本创建者sys_user.user_id',
  `provider_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL DEFAULT NULL,
  `execution_binding` json NULL,
  `fallback_voice_version_id` bigint NULL DEFAULT NULL,
  `reference_asset_id` bigint NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uq_p_voice_version_1`(`voice_id` ASC, `version_no` ASC) USING BTREE,
  INDEX `idx_p_voice_version_1`(`account_id` ASC, `voice_id` ASC) USING BTREE,
  INDEX `idx_voice_fallback`(`fallback_voice_version_id` ASC) USING BTREE,
  CONSTRAINT `fk_p_voice_version_voice_id` FOREIGN KEY (`voice_id`) REFERENCES `p_voice` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_voice_fallback` FOREIGN KEY (`fallback_voice_version_id`) REFERENCES `p_voice_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_p_voice_version_account_id` CHECK (`account_id` > 0),
  CONSTRAINT `ck_p_voice_version_id` CHECK (`id` > 0),
  CONSTRAINT `ck_p_voice_version_official_service_id` CHECK (`official_service_id` > 0),
  CONSTRAINT `ck_p_voice_version_sample_file_id` CHECK (`sample_file_id` > 0),
  CONSTRAINT `ck_p_voice_version_service_reference` CHECK (`official_service_id` is not null),
  CONSTRAINT `ck_p_voice_version_service_type` CHECK (`service_type` = _utf8mb4'OFFICIAL'),
  CONSTRAINT `ck_p_voice_version_version_no` CHECK (`version_no` > 0),
  CONSTRAINT `ck_p_voice_version_voice_id` CHECK (`voice_id` > 0)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_as_cs COMMENT = 'Voice配置版本' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_config
-- ----------------------------
DROP TABLE IF EXISTS `sys_config`;
CREATE TABLE `sys_config`  (
  `config_id` int NOT NULL AUTO_INCREMENT COMMENT '参数主键',
  `config_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '参数名称',
  `config_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '参数键名',
  `config_value` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '参数键值',
  `config_type` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'N' COMMENT '系统内置（Y是 N否）',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`config_id`) USING BTREE,
  UNIQUE INDEX `uq_sys_config_config_key`(`config_key` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 104 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '参数配置表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_dept
-- ----------------------------
DROP TABLE IF EXISTS `sys_dept`;
CREATE TABLE `sys_dept`  (
  `dept_id` bigint NOT NULL AUTO_INCREMENT COMMENT '部门id',
  `parent_id` bigint NULL DEFAULT 0 COMMENT '父部门id',
  `ancestors` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '祖级列表',
  `dept_name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '部门名称',
  `order_num` int NULL DEFAULT 0 COMMENT '显示顺序',
  `leader` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '负责人',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '联系电话',
  `email` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '邮箱',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '部门状态（0正常 1停用）',
  `del_flag` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`dept_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 200 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '部门表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_dict_data
-- ----------------------------
DROP TABLE IF EXISTS `sys_dict_data`;
CREATE TABLE `sys_dict_data`  (
  `dict_code` bigint NOT NULL AUTO_INCREMENT COMMENT '字典编码',
  `dict_sort` int NULL DEFAULT 0 COMMENT '字典排序',
  `dict_label` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '字典标签',
  `dict_value` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '字典键值',
  `dict_type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '字典类型',
  `css_class` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '样式属性（其他样式扩展）',
  `list_class` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '表格回显样式',
  `is_default` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'N' COMMENT '是否默认（Y是 N否）',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '状态（0正常 1停用）',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`dict_code`) USING BTREE,
  UNIQUE INDEX `uq_sys_dict_data_type_value`(`dict_type` ASC, `dict_value` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 100 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '字典数据表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_dict_type
-- ----------------------------
DROP TABLE IF EXISTS `sys_dict_type`;
CREATE TABLE `sys_dict_type`  (
  `dict_id` bigint NOT NULL AUTO_INCREMENT COMMENT '字典主键',
  `dict_name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '字典名称',
  `dict_type` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '字典类型',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '状态（0正常 1停用）',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`dict_id`) USING BTREE,
  UNIQUE INDEX `uq_sys_dict_type_dict_type`(`dict_type` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 100 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '字典类型表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_job
-- ----------------------------
DROP TABLE IF EXISTS `sys_job`;
CREATE TABLE `sys_job`  (
  `job_id` bigint NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  `job_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '任务名称',
  `job_group` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'DEFAULT' COMMENT '任务组名',
  `invoke_target` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '调用目标字符串',
  `cron_expression` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT 'cron执行表达式',
  `misfire_policy` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '3' COMMENT '计划执行错误策略（1立即执行 2执行一次 3放弃执行）',
  `concurrent` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '1' COMMENT '是否并发执行（0允许 1禁止）',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '状态（0正常 1暂停）',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '备注信息',
  PRIMARY KEY (`job_id`, `job_name`, `job_group`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 100 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '定时任务调度表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_job_log
-- ----------------------------
DROP TABLE IF EXISTS `sys_job_log`;
CREATE TABLE `sys_job_log`  (
  `job_log_id` bigint NOT NULL AUTO_INCREMENT COMMENT '任务日志ID',
  `job_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '任务名称',
  `job_group` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '任务组名',
  `invoke_target` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '调用目标字符串',
  `job_message` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '日志信息',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '执行状态（0正常 1失败）',
  `exception_info` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '异常信息',
  `start_time` datetime NULL DEFAULT NULL COMMENT '执行开始时间',
  `end_time` datetime NULL DEFAULT NULL COMMENT '执行结束时间',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`job_log_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '定时任务调度日志表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_logininfor
-- ----------------------------
DROP TABLE IF EXISTS `sys_logininfor`;
CREATE TABLE `sys_logininfor`  (
  `info_id` bigint NOT NULL AUTO_INCREMENT COMMENT '访问ID',
  `user_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '用户账号',
  `ipaddr` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '登录IP地址',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '登录状态（0成功 1失败）',
  `msg` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '提示信息',
  `access_time` datetime NULL DEFAULT NULL COMMENT '访问时间',
  PRIMARY KEY (`info_id`) USING BTREE,
  INDEX `idx_sys_logininfor_s`(`status` ASC) USING BTREE,
  INDEX `idx_sys_logininfor_lt`(`access_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 244 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '系统访问记录' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_menu
-- ----------------------------
DROP TABLE IF EXISTS `sys_menu`;
CREATE TABLE `sys_menu`  (
  `menu_id` bigint NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
  `menu_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '菜单名称',
  `parent_id` bigint NULL DEFAULT 0 COMMENT '父菜单ID',
  `order_num` int NULL DEFAULT 0 COMMENT '显示顺序',
  `path` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '路由地址',
  `component` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '组件路径',
  `query` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '路由参数',
  `route_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '路由名称',
  `is_frame` int NULL DEFAULT 1 COMMENT '是否为外链（0是 1否）',
  `is_cache` int NULL DEFAULT 0 COMMENT '是否缓存（0缓存 1不缓存）',
  `menu_type` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '菜单类型（M目录 C菜单 F按钮）',
  `visible` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '菜单状态（0显示 1隐藏）',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '菜单状态（0正常 1停用）',
  `perms` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '权限标识',
  `icon` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '#' COMMENT '菜单图标',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '备注',
  PRIMARY KEY (`menu_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2003 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '菜单权限表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_oper_log
-- ----------------------------
DROP TABLE IF EXISTS `sys_oper_log`;
CREATE TABLE `sys_oper_log`  (
  `oper_id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志主键',
  `title` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '模块标题',
  `business_type` int NULL DEFAULT 0 COMMENT '业务类型（0其它 1新增 2修改 3删除）',
  `method` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '方法名称',
  `request_method` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '请求方式',
  `operator_type` int NULL DEFAULT 0 COMMENT '操作类别（0其它 1后台用户 2手机端用户）',
  `oper_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '操作人员',
  `dept_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '部门名称',
  `oper_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '请求URL',
  `oper_ip` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '主机地址',
  `oper_location` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '操作地点',
  `oper_param` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '请求参数',
  `json_result` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '返回参数',
  `status` int NULL DEFAULT 0 COMMENT '操作状态（0正常 1异常）',
  `error_msg` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '错误消息',
  `oper_time` datetime NULL DEFAULT NULL COMMENT '操作时间',
  `cost_time` bigint NULL DEFAULT 0 COMMENT '消耗时间',
  PRIMARY KEY (`oper_id`) USING BTREE,
  INDEX `idx_sys_oper_log_bt`(`business_type` ASC) USING BTREE,
  INDEX `idx_sys_oper_log_s`(`status` ASC) USING BTREE,
  INDEX `idx_sys_oper_log_ot`(`oper_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 247 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '操作日志记录' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_post
-- ----------------------------
DROP TABLE IF EXISTS `sys_post`;
CREATE TABLE `sys_post`  (
  `post_id` bigint NOT NULL AUTO_INCREMENT COMMENT '岗位ID',
  `post_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '岗位编码',
  `post_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '岗位名称',
  `post_sort` int NOT NULL COMMENT '显示顺序',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '状态（0正常 1停用）',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`post_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '岗位信息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_role
-- ----------------------------
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role`  (
  `role_id` bigint NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `role_name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '角色名称',
  `role_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '角色权限字符串',
  `role_sort` int NOT NULL COMMENT '显示顺序',
  `data_scope` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '1' COMMENT '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限）',
  `menu_check_strictly` tinyint(1) NULL DEFAULT 1 COMMENT '菜单树选择项是否关联显示',
  `dept_check_strictly` tinyint(1) NULL DEFAULT 1 COMMENT '部门树选择项是否关联显示',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '角色状态（0正常 1停用）',
  `del_flag` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`role_id`) USING BTREE,
  UNIQUE INDEX `uq_sys_role_role_key`(`role_key` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 910003 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色信息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_role_dept
-- ----------------------------
DROP TABLE IF EXISTS `sys_role_dept`;
CREATE TABLE `sys_role_dept`  (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `dept_id` bigint NOT NULL COMMENT '部门ID',
  PRIMARY KEY (`role_id`, `dept_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色和部门关联表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_role_menu
-- ----------------------------
DROP TABLE IF EXISTS `sys_role_menu`;
CREATE TABLE `sys_role_menu`  (
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `menu_id` bigint NOT NULL COMMENT '菜单ID',
  PRIMARY KEY (`role_id`, `menu_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色和菜单关联表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_user
-- ----------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user`  (
  `user_id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `dept_id` bigint NULL DEFAULT NULL COMMENT '部门ID',
  `user_name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户账号',
  `nick_name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户昵称',
  `user_type` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '00' COMMENT '用户类型（00系统用户）',
  `email` varchar(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '注册邮箱原展示值',
  `email_normalized` varchar(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '平台规范化注册邮箱',
  `email_verified_at` datetime(3) NULL DEFAULT NULL COMMENT '邮箱验证完成时间，UTC',
  `auth_epoch` bigint NOT NULL DEFAULT 1 COMMENT '账号持久撤销版本',
  `avatar_file_id` bigint NULL DEFAULT NULL COMMENT '账号头像p_file逻辑引用',
  `phonenumber` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '手机号码',
  `sex` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '用户性别（0男 1女 2未知）',
  `avatar` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '头像地址',
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '若依格式密码哈希',
  `status` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '账号状态（0正常 1停用）',
  `del_flag` char(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  `login_ip` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '最后登录IP',
  `login_date` datetime NULL DEFAULT NULL COMMENT '最后登录时间',
  `pwd_update_date` datetime NULL DEFAULT NULL COMMENT '密码最后更新时间',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '创建者',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '' COMMENT '更新者',
  `update_time` datetime NULL DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`user_id`) USING BTREE,
  UNIQUE INDEX `uq_sys_user_user_name`(`user_name` ASC) USING BTREE,
  UNIQUE INDEX `uq_sys_user_email_normalized`(`email_normalized` ASC) USING BTREE,
  CONSTRAINT `ck_sys_user_auth_epoch` CHECK (`auth_epoch` > 0)
) ENGINE = InnoDB AUTO_INCREMENT = 910107 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户信息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_user_post
-- ----------------------------
DROP TABLE IF EXISTS `sys_user_post`;
CREATE TABLE `sys_user_post`  (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `post_id` bigint NOT NULL COMMENT '岗位ID',
  PRIMARY KEY (`user_id`, `post_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户与岗位关联表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_user_role
-- ----------------------------
DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role`  (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  PRIMARY KEY (`user_id`, `role_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户和角色关联表' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
