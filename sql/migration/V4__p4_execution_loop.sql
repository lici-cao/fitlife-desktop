-- ============================================================
-- P4：执行闭环与统计（打卡关联计划明细 + 日汇总表 + 汇总触发器）
-- 幂等：可重复执行；空操作分支用 DO 0（不打印噪音）。
-- 兼容 MySQL 5.7 / MariaDB 10.x（无 IF NOT EXISTS 的列/索引用 information_schema + PREPARE）。
-- 原则：只加列/加表/加索引，不改旧列类型，旧接口零破坏。
-- ============================================================

-- ------------------------------------------------------------
-- 1. workout_records 增列：打卡与计划明细关联
--    plan_detail_id  指向 plan_details.id（可空：自由记录）
--    plan_day        计划内第几天 1-7（冗余，便于聚合）
--    status          完成状态：DONE / SKIPPED / PARTIAL（旧行为默认 DONE）
--    skipped_reasons 跳过原因，逗号分隔（疼/太累/没时间/器械占用）
-- ------------------------------------------------------------
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `workout_records` ADD COLUMN `plan_detail_id` BIGINT NULL COMMENT ''关联 plan_details.id，空=自由记录'' AFTER `exercise_name`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'workout_records' AND column_name = 'plan_detail_id'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `workout_records` ADD COLUMN `plan_day` INT NULL COMMENT ''计划内第几天 1-7'' AFTER `plan_detail_id`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'workout_records' AND column_name = 'plan_day'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `workout_records` ADD COLUMN `status` VARCHAR(12) NOT NULL DEFAULT ''DONE'' COMMENT ''DONE / SKIPPED / PARTIAL'' AFTER `calories_burned`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'workout_records' AND column_name = 'status'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `workout_records` ADD COLUMN `skipped_reasons` VARCHAR(120) NULL COMMENT ''跳过原因，逗号分隔'' AFTER `status`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'workout_records' AND column_name = 'skipped_reasons'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `workout_records` ADD COLUMN `rpe` INT NULL COMMENT ''用户上报的实际主观强度 1-10'' AFTER `skipped_reasons`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'workout_records' AND column_name = 'rpe'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `workout_records` ADD COLUMN `pain_flag` TINYINT NOT NULL DEFAULT 0 COMMENT ''1=当日上报疼痛'' AFTER `rpe`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'workout_records' AND column_name = 'pain_flag'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 2. daily_records 增列：热量收支与训练量快照
--    由 P4 的 DailySummaryService 在事务内回写，Dashboard 只读这里
-- ------------------------------------------------------------
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `daily_records` ADD COLUMN `net_calories` DOUBLE NULL COMMENT ''净热量 = 摄入 - 消耗'' AFTER `target_calories`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'daily_records' AND column_name = 'net_calories'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `daily_records` ADD COLUMN `workout_minutes` INT NULL DEFAULT 0 COMMENT ''当日训练分钟数'' AFTER `net_calories`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'daily_records' AND column_name = 'workout_minutes'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `daily_records` ADD COLUMN `completed_items` INT NULL DEFAULT 0 COMMENT ''当日完成动作数'' AFTER `workout_minutes`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'daily_records' AND column_name = 'completed_items'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `daily_records` ADD COLUMN `planned_items` INT NULL DEFAULT 0 COMMENT ''当日应做动作数（取当天生效计划）'' AFTER `completed_items`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'daily_records' AND column_name = 'planned_items'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `daily_records` ADD COLUMN `avg_rpe` DOUBLE NULL COMMENT ''当日主观强度均值'' AFTER `planned_items`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'daily_records' AND column_name = 'avg_rpe'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `daily_records` ADD COLUMN `pain_flag` TINYINT NULL DEFAULT 0 COMMENT ''1=当日上报疼痛'' AFTER `avg_rpe`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'daily_records' AND column_name = 'pain_flag'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 3. 打卡索引
--    注意：不用 UNIQUE(user_id, plan_detail_id, date) —— 同一动作当天允许分次完成，
--    唯一键会造成"第二组插不进去"的功能倒退；改为普通索引 + 应用层累加。
-- ------------------------------------------------------------
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `workout_records` ADD KEY `idx_workout_plan_detail` (`user_id`, `plan_detail_id`, `date`)',
    'DO 0')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'workout_records'
    AND index_name = 'idx_workout_plan_detail'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 4. plan_adherence：按「计划版本 × 日」的执行快照（依从率的数据底座）
--    unique(user_id, plan_id, date)：一天一条，重复打卡在同一行累加
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `plan_adherence` (
  `id`              BIGINT      NOT NULL AUTO_INCREMENT,
  `user_id`         BIGINT      NOT NULL,
  `plan_id`         BIGINT      NOT NULL COMMENT 'plans.id（具体版本，不随版本切换变化）',
  `date`            DATE        NOT NULL,
  `planned_items`   INT         NOT NULL DEFAULT 0,
  `done_items`      INT         NOT NULL DEFAULT 0,
  `skipped_items`   INT         NOT NULL DEFAULT 0,
  `minutes`         INT         NOT NULL DEFAULT 0,
  `calories`        DOUBLE      NOT NULL DEFAULT 0,
  `avg_rpe`         DOUBLE      NULL,
  `pain_flag`       TINYINT     NOT NULL DEFAULT 0 COMMENT '1=当日上报疼痛，触发进阶阻挡',
  `created_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pa_user_plan_date` (`user_id`, `plan_id`, `date`),
  KEY `idx_pa_user_date` (`user_id`, `date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计划执行依从率快照（每次打卡后重算当日行）';

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plan_adherence` ADD UNIQUE KEY `uk_pa_user_plan_date` (`user_id`, `plan_id`, `date`)',
    'DO 0')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'plan_adherence'
    AND index_name = 'uk_pa_user_plan_date'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plan_adherence` ADD KEY `idx_pa_user_date` (`user_id`, `date`)',
    'DO 0')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'plan_adherence'
    AND index_name = 'idx_pa_user_date'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 5. 风险规则入库（P4 补齐「风险提示分级」）
--    复用 P2 的 knowledge_rule 表，新增 ADVICE_LOAD 一条：
--    依从率不足 / RPE 偏高 / 疼痛 三类信号 → 建议降载还是换动作
-- ------------------------------------------------------------
INSERT IGNORE INTO `knowledge_rule` (`code`, `name`, `category`, `expression`, `severity`, `enabled`, `version`, `note`, `updated_at`)
VALUES ('ADVICE_LOAD', '执行反馈降载建议', 'ADVICE',
        '{"adherenceLow":70,"adherenceHigh":90,"rpeOver":1.0,"rpeUnder":-1.5,"painBlockAdvance":true,"minSessionsForAdvice":2}',
        'INFO', 1, 1, 'P4：依从率<70% 或 实际RPE 比计划高 1.0 以上 → 建议降载；疼痛日报阻断进阶', NOW());

-- ------------------------------------------------------------
-- 6. 历史数据回填（幂等：只补空值，不覆盖已有）
-- ------------------------------------------------------------
UPDATE `workout_records` SET `status` = 'DONE' WHERE `status` IS NULL OR `status` = '';

UPDATE `daily_records`
   SET `net_calories` = COALESCE(`food_intake`, 0) - COALESCE(`exercise_burn`, 0)
 WHERE `net_calories` IS NULL;

UPDATE `daily_records`
   SET `workout_minutes` = 0
 WHERE `workout_minutes` IS NULL;

UPDATE `daily_records`
   SET `completed_items` = 0
 WHERE `completed_items` IS NULL;

UPDATE `daily_records`
   SET `planned_items` = 0
 WHERE `planned_items` IS NULL;