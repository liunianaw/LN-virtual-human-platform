-- Calling-fact reviews and administrative task operations.  Existing V1 calling tables remain authoritative.
CREATE TABLE IF NOT EXISTS `p_call_review` (
  `id` bigint NOT NULL,
  `call_id` bigint NOT NULL,
  `actor_id` bigint NOT NULL,
  `reviewed_status` varchar(24) NOT NULL,
  `evidence_note` varchar(1000) NOT NULL,
  `cost_amount` decimal(18,6) NULL,
  `currency` char(3) NULL,
  `cost_source` varchar(20) NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_p_call_review_1` (`call_id`,`created_at`),
  CONSTRAINT `ck_p_call_review_status` CHECK (`reviewed_status` IN ('SUCCEEDED','FAILED','CANCELLED')),
  CONSTRAINT `ck_p_call_review_cost_amount` CHECK (`cost_amount` >= 0),
  CONSTRAINT `ck_p_call_review_cost_source` CHECK (`cost_source` IN ('CONSOLE','ESTIMATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='管理员调用事实核对审计';

CREATE TABLE IF NOT EXISTS `p_generation_reconciliation_audit` (
  `id` bigint NOT NULL,
  `attempt_id` bigint NOT NULL,
  `task_id` bigint NOT NULL,
  `actor_id` bigint NOT NULL,
  `reason` varchar(500) NOT NULL,
  `status` varchar(20) NOT NULL,
  `created_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_p_generation_reconciliation_1` (`attempt_id`,`created_at`),
  CONSTRAINT `ck_p_generation_reconciliation_status` CHECK (`status` IN ('COMPLETED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs COMMENT='管理员原任务核对审计';

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark)
VALUES
  (2000,'平台运营',0,20,'operations',NULL,'','',1,0,'M','0','0','','monitor','migration',NOW(),'',NULL,'平台运营目录'),
  (2001,'任务与调用',2000,1,'tasks','operations/index','', 'PlatformOperations',1,0,'C','0','0','platform:operations:read','list','migration',NOW(),'',NULL,'跨账号任务与调用诊断'),
  (2002,'核对任务',2001,1,'','','', '',1,0,'F','0','0','platform:operations:reconcile','#','migration',NOW(),'',NULL,'安全核对原任务或调用事实')
ON DUPLICATE KEY UPDATE menu_id=menu_id;

INSERT INTO sys_role_menu (role_id,menu_id) VALUES (1,2000),(1,2001),(1,2002)
ON DUPLICATE KEY UPDATE role_id=role_id;
