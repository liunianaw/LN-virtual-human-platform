-- DEV-11 retains webhook business facts; technical Outbox cleanup remains separate.
UPDATE p_webhook_delivery SET expires_at=NULL WHERE expires_at IS NOT NULL;
ALTER TABLE p_webhook_delivery
  ADD CONSTRAINT ck_p_webhook_delivery_long_term CHECK (expires_at IS NULL);

INSERT INTO sys_menu
  (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark)
VALUES
  (1160,'用量与额度',1,12,'usage','developer/usage/index','','DeveloperUsage',1,0,'C','0','0','platform:usage:read','chart','migration',NOW(),'',NULL,'调用事实、日汇总及剩余额度'),
  (1161,'Webhook',1,13,'webhook-endpoints','developer/webhook/index','','DeveloperWebhooks',1,0,'C','0','0','platform:webhook:read','link','migration',NOW(),'',NULL,'Avatar 任务通知与投递记录'),
  (1162,'Webhook 配置',1161,1,'','','','',1,0,'F','0','0','platform:webhook:write','#','migration',NOW(),'',NULL,'创建、轮换和停用'),
  (1163,'用量与额度核对',0,1,'','','','',1,0,'F','1','0','platform:operations:reconcile','#','migration',NOW(),'',NULL,'管理员修复汇总与核对预占')
ON DUPLICATE KEY UPDATE menu_id=menu_id;

INSERT INTO sys_role_menu (role_id,menu_id) VALUES
  (1,1160),(1,1161),(1,1162),(1,1163),(2,1160),(2,1161),(2,1162)
ON DUPLICATE KEY UPDATE role_id=role_id;
