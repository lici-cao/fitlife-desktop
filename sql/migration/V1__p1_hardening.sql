-- ============================================================
-- P1 数据硬化迁移：唯一约束与索引 / 幂等键 / 审计日志 / 角色
--
-- 设计原则
--   1. 幂等：可以重复执行，执行前先查 information_schema，已经改过就跳过；
--   2. 不破坏已有数据：daily_records 先去重再加唯一约束，保留 id 最大的一条；
--   3. 只做加法：不改列类型、不删列，老代码回滚后仍然能跑。
--
-- 执行方式
--   mysql -u root -p fitlife < sql/migration/V1__p1_hardening.sql
--   或由一键脚本 scripts/p1-oneclick.ps1 自动执行
-- ============================================================

-- ------------------------------------------------------------
-- 1. users：角色 / 状态 / 最近登录时间
-- ------------------------------------------------------------
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `users` ADD COLUMN `role` VARCHAR(20) NOT NULL DEFAULT ''USER'' COMMENT ''USER / ADMIN'' AFTER `password`',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'role'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `users` ADD COLUMN `status` VARCHAR(20) NOT NULL DEFAULT ''ACTIVE'' COMMENT ''ACTIVE / DISABLED'' AFTER `role`',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'status'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `users` ADD COLUMN `last_login_at` DATETIME NULL AFTER `created_at`',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'last_login_at'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `users` ADD INDEX `idx_users_role` (`role`)',
    'DO 0')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND INDEX_NAME = 'idx_users_role'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 2. daily_records：(user_id, date) 唯一
--    原表没有任何唯一约束，并发首插会生成两条当天记录，
--    之后每次回写只更新其中一条，统计就会漂。
--    先去重（保留 id 最大者 = 最近写入），再加唯一索引。
-- ------------------------------------------------------------
DELETE d1 FROM `daily_records` d1
JOIN `daily_records` d2
  ON d1.`user_id` = d2.`user_id`
 AND d1.`date`    = d2.`date`
 AND d1.`id` < d2.`id`;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `daily_records` ADD UNIQUE KEY `uk_daily_user_date` (`user_id`, `date`)',
    'DO 0')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'daily_records' AND INDEX_NAME = 'uk_daily_user_date'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 3. 明细表单列索引：按 (user_id, date) 查当日明细是最高频查询，
--    原表连索引都没有，数据量一大就是全表扫描。
-- ------------------------------------------------------------
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `food_intakes` ADD INDEX `idx_food_user_date` (`user_id`, `date`)',
    'DO 0')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'food_intakes' AND INDEX_NAME = 'idx_food_user_date'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `workout_records` ADD INDEX `idx_workout_user_date` (`user_id`, `date`)',
    'DO 0')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workout_records' AND INDEX_NAME = 'idx_workout_user_date'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plans` ADD INDEX `idx_plans_user_created` (`user_id`, `created_at`)',
    'DO 0')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'plans' AND INDEX_NAME = 'idx_plans_user_created'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 4. 幂等键表
--    status：0 = 处理中，1 = 已完成
--    唯一约束落在 (user_id, idem_key)：同一用户重复提交同一个 Key 才能命中；
--    不同用户互不影响。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `idempotency_key` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `idem_key`      VARCHAR(128) NOT NULL COMMENT '请求头 Idempotency-Key',
  `user_id`       BIGINT       NOT NULL,
  `endpoint`      VARCHAR(160) NOT NULL COMMENT '方法+路径，防止同 Key 跨接口复用',
  `request_hash`  VARCHAR(64)  NOT NULL COMMENT '请求参数 SHA-256',
  `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 处理中 / 1 已完成',
  `response_body` LONGTEXT     NULL COMMENT '成功响应体，用于回放',
  `created_at`    DATETIME     NOT NULL,
  `updated_at`    DATETIME     NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem_user_key` (`user_id`, `idem_key`),
  KEY `idx_idem_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口幂等键';

-- ------------------------------------------------------------
-- 5. 审计日志表
--    只记「谁对哪个资源做了什么」，不记业务正文（身高体重、伤病等）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `audit_log` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT       NULL,
  `username`   VARCHAR(64)  NULL,
  `action`     VARCHAR(64)  NOT NULL COMMENT 'PLAN_ACCESS_DENIED / USER_ROLE_CHANGE ...',
  `target`     VARCHAR(32)  NULL COMMENT 'PLAN / CUSTOM_FOOD / USER',
  `target_id`  BIGINT       NULL,
  `detail`     VARCHAR(512) NULL,
  `ip`         VARCHAR(64)  NULL,
  `created_at` DATETIME     NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_audit_user` (`user_id`),
  KEY `idx_audit_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计';

-- ------------------------------------------------------------
-- 6. 演示账号升级为管理员（后台管理入口需要它）
--    只改 role，不动密码。
-- ------------------------------------------------------------
UPDATE `users` SET `role` = 'ADMIN' WHERE `username` = 'dandan';