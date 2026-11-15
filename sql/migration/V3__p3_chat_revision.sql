-- ============================================================
-- P3：对话与计划局部修订（会话持久化 + 修订链路）
-- 幂等：可重复执行；空操作分支用 DO 0（不打印噪音）。
-- 兼容 MySQL 5.7（无 IF NOT EXISTS 的列/索引用 information_schema + PREPARE）。
-- ============================================================

-- ---------- chat_session：会话主表 ----------
CREATE TABLE IF NOT EXISTS `chat_session` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `session_key`   VARCHAR(64)  NULL COMMENT '前端生成的会话标识（UUID），兼容旧前端',
  `user_id`       BIGINT       NOT NULL,
  `plan_id`       BIGINT       NULL COMMENT '会话当前聚焦的计划版本',
  `title`         VARCHAR(120) NULL,
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_active_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cs_session_key` (`session_key`),
  KEY `idx_cs_user` (`user_id`, `last_active_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 对话会话（服务端持久化）';

-- ---------- chat_message：消息明细表 ----------
CREATE TABLE IF NOT EXISTS `chat_message` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `session_id` BIGINT       NOT NULL,
  `role`       VARCHAR(12)  NOT NULL COMMENT 'user / assistant / system',
  `content`    LONGTEXT     NULL,
  `action`     VARCHAR(24)  NULL COMMENT 'GENERATE / REVISE / QUERY / CHAT / STREAM',
  `tokens`     INT          NULL,
  `refs`       VARCHAR(512) NULL COMMENT '关联实体引用，如 planId/version/knowledge codes',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_cm_session` (`session_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 对话消息（跨设备可恢复，不再依赖浏览器 localStorage）';

-- ---------- 幂等索引兜底（表已存在但缺索引的旧库） ----------
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `chat_session` ADD UNIQUE KEY `uk_cs_session_key` (`session_key`)',
    'DO 0')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'chat_session' AND index_name = 'uk_cs_session_key'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `chat_session` ADD KEY `idx_cs_user` (`user_id`, `last_active_at`)',
    'DO 0')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'chat_session' AND index_name = 'idx_cs_user'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `chat_message` ADD KEY `idx_cm_session` (`session_id`, `id`)',
    'DO 0')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'chat_message' AND index_name = 'idx_cm_session'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 完成标记 ----------
SELECT 'P3 chat tables ready' AS status,
       (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name IN ('chat_session','chat_message')) AS tables_created;
