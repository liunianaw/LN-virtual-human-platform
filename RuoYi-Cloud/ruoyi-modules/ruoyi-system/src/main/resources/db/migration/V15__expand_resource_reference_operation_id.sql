-- Application publish prefixes a 64-character Idempotency-Key with its application identifier.
ALTER TABLE p_resource_reference MODIFY COLUMN operation_id varchar(128) NOT NULL COMMENT '引用操作幂等标识';
