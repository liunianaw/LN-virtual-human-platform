# SQL 使用边界

- ry_20260417.sql：若依原始业务建表与种子数据，只供空的隔离开发库初始化或编写正式迁移时参考，含 DROP TABLE，不能重放到已有库。
- ry_config_20260818.sql：若依原始 Nacos 库快照，包含已裁剪的配置，不能作为当前运行配置。当前模板以 ../config/nacos/ 为准；正式 Nacos 建库应使用匹配其版本的官方 schema。
- ry_seata_20210128.sql：来源参考，当前不使用、不导入。
- quartz.sql：保留的 Quartz JDBC schema。现有 job 仍使用上游默认内存调度器，任务定义/日志保存在 sys_job/sys_job_log；没有在本轮切换到 Quartz JDBC。将来切换时单独迁移和验收。
- trim-existing-framework.sql：对已有框架库显式执行的可重复菜单停用脚本，不删用户、角色、部门兼容表或任务数据。执行后重新登录。
- enable-devtools-menu.sql：开发工具环境按需启用生成器菜单。

本轮未执行任何 SQL。正式 platform_db/session_db 的 Flyway 基线和升级迁移属于工程骨架下一步；这里不宣称已有可重复的全库迁移。
