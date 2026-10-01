CREATE TABLE `p_point_rate_version` (
  `id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `version_no` bigint NOT NULL,
  `generation_action_cent` bigint NOT NULL,
  `tts_character_cent` bigint NOT NULL,
  `storage_byte_cent` bigint NOT NULL,
  `effective_at` datetime(3) NOT NULL,
  `created_by` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_p_point_rate_version_no` (`version_no`),
  KEY `idx_p_point_rate_effective` (`effective_at`,`version_no`),
  CONSTRAINT `ck_p_point_rate_values` CHECK (`generation_action_cent` >= 0 AND `tts_character_cent` >= 0 AND `storage_byte_cent` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Published point rates; 100 cent units equal one point';

CREATE TABLE `p_point_balance` (
  `account_id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `granted_cent` bigint NOT NULL DEFAULT 0,
  `used_cent` bigint NOT NULL DEFAULT 0,
  `reserved_cent` bigint NOT NULL DEFAULT 0,
  `revision` bigint NOT NULL DEFAULT 1,
  PRIMARY KEY (`account_id`),
  CONSTRAINT `ck_p_point_balance_values` CHECK (`granted_cent` >= 0 AND `used_cent` >= 0 AND `reserved_cent` >= 0 AND `used_cent` + `reserved_cent` <= `granted_cent`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Account point balance in cent units';

CREATE TABLE `p_point_reservation` (
  `id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `updated_at` datetime(3) NOT NULL,
  `account_id` bigint NOT NULL,
  `business_type` varchar(24) NOT NULL,
  `business_id` varchar(128) NOT NULL,
  `reservation_no` int NOT NULL,
  `billing_item` varchar(32) NOT NULL,
  `measured_units` bigint NOT NULL,
  `unit_price_cent` bigint NOT NULL,
  `rate_version_id` bigint NOT NULL,
  `reserved_cent` bigint NOT NULL,
  `settled_cent` bigint NOT NULL DEFAULT 0,
  `state` varchar(24) NOT NULL,
  `settled_at` datetime(3) NULL,
  `released_at` datetime(3) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_p_point_reservation_business` (`account_id`,`business_type`,`business_id`,`reservation_no`),
  KEY `idx_p_point_reservation_account` (`account_id`,`created_at`),
  CONSTRAINT `fk_p_point_reservation_rate` FOREIGN KEY (`rate_version_id`) REFERENCES `p_point_rate_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_p_point_reservation_values` CHECK (`reservation_no` > 0 AND `measured_units` > 0 AND `unit_price_cent` >= 0 AND `reserved_cent` >= 0 AND `settled_cent` >= 0 AND `settled_cent` <= `reserved_cent`),
  CONSTRAINT `ck_p_point_reservation_item` CHECK (`billing_item` IN ('GENERATION_ACTION','TTS_CHARACTER')),
  CONSTRAINT `ck_p_point_reservation_state` CHECK (`state` IN ('RESERVED','SETTLED','RELEASED','REVIEW_REQUIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Price snapshots and point reservations';

CREATE TABLE `p_point_entry` (
  `id` bigint NOT NULL,
  `created_at` datetime(3) NOT NULL,
  `account_id` bigint NOT NULL,
  `reservation_id` bigint NULL,
  `event_key` varchar(160) NOT NULL,
  `entry_type` varchar(16) NOT NULL,
  `delta_granted_cent` bigint NOT NULL DEFAULT 0,
  `delta_used_cent` bigint NOT NULL DEFAULT 0,
  `delta_reserved_cent` bigint NOT NULL DEFAULT 0,
  `operator_id` bigint NULL,
  `reason` varchar(500) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_p_point_entry_event` (`account_id`,`event_key`),
  KEY `idx_p_point_entry_account` (`account_id`,`created_at`),
  CONSTRAINT `fk_p_point_entry_reservation` FOREIGN KEY (`reservation_id`) REFERENCES `p_point_reservation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_p_point_entry_type` CHECK (`entry_type` IN ('GRANT','RESERVE','SETTLE','RELEASE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Immutable point ledger';

INSERT INTO `p_point_rate_version`
  (`id`,`created_at`,`version_no`,`generation_action_cent`,`tts_character_cent`,`storage_byte_cent`,`effective_at`,`created_by`)
VALUES (uuid_short(),utc_timestamp(3),1,5000,1,0,utc_timestamp(3),1);
