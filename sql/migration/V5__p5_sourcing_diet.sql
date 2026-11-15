-- ============================================================
-- P5：内容寻源 / 审核治理 / 计划含饮食
-- 幂等：可重复执行；空操作分支用 DO 0（不打印噪音）。
-- 兼容 MySQL 5.7 / MariaDB 10.x（列/索引用 information_schema + PREPARE）。
-- 原则：只加列/加表/加索引 + 回填来源，不改旧列类型，旧接口零破坏。
-- ============================================================

-- ------------------------------------------------------------
-- 1. exercises 增列：每条动作的「出处」与「审核状态」
--    source_type    来源类型：GUIDELINE / TEXTBOOK / JOURNAL / DATABASE / COMMUNITY
--    evidence_level 证据等级：A（指南/立场声明）/ B（教材/综述）/ C（社区/经验）
--    review_status  APPROVED（已审）/ PENDING（待审）/ REJECTED（驳回）
-- ------------------------------------------------------------
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exercises` ADD COLUMN `source_type` VARCHAR(24) NULL COMMENT ''GUIDELINE/TEXTBOOK/JOURNAL/DATABASE/COMMUNITY'' AFTER `contraindication`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'exercises' AND column_name = 'source_type'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exercises` ADD COLUMN `source_title` VARCHAR(180) NULL COMMENT ''来源标题（书名/指南名/数据库条目）'' AFTER `source_type`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'exercises' AND column_name = 'source_title'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exercises` ADD COLUMN `source_url` VARCHAR(255) NULL COMMENT ''可点击的寻源链接'' AFTER `source_title`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'exercises' AND column_name = 'source_url'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exercises` ADD COLUMN `source_page` VARCHAR(64) NULL COMMENT ''章节/条目/页码'' AFTER `source_url`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'exercises' AND column_name = 'source_page'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exercises` ADD COLUMN `evidence_level` VARCHAR(4) NULL COMMENT ''A/B/C 证据等级'' AFTER `source_page`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'exercises' AND column_name = 'evidence_level'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exercises` ADD COLUMN `license_note` VARCHAR(120) NULL COMMENT ''使用许可/引用说明'' AFTER `evidence_level`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'exercises' AND column_name = 'license_note'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exercises` ADD COLUMN `review_status` VARCHAR(16) NOT NULL DEFAULT ''APPROVED'' COMMENT ''APPROVED/PENDING/REJECTED'' AFTER `license_note`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'exercises' AND column_name = 'review_status'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exercises` ADD COLUMN `submitted_by` BIGINT NULL COMMENT ''投稿用户 id，系统内置为 NULL'' AFTER `review_status`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'exercises' AND column_name = 'submitted_by'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exercises` ADD COLUMN `reviewed_by` BIGINT NULL COMMENT ''审核人用户 id'' AFTER `submitted_by`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'exercises' AND column_name = 'reviewed_by'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exercises` ADD COLUMN `reviewed_at` DATETIME NULL COMMENT ''审核时间'' AFTER `reviewed_by`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'exercises' AND column_name = 'reviewed_at'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exercises` ADD COLUMN `review_note` VARCHAR(255) NULL COMMENT ''审核意见（通过/驳回理由）'' AFTER `reviewed_at`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'exercises' AND column_name = 'review_note'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exercises` ADD COLUMN `last_verified_at` DATE NULL COMMENT ''来源链接最近一次人工核验日期'' AFTER `review_note`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'exercises' AND column_name = 'last_verified_at'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exercises` ADD INDEX `idx_ex_review` (`review_status`)',
    'DO 0')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'exercises' AND index_name = 'idx_ex_review'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `exercises` ADD INDEX `idx_ex_source` (`source_type`)',
    'DO 0')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'exercises' AND index_name = 'idx_ex_source'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 2. knowledge_item 增列：知识条目也要能寻源
-- ------------------------------------------------------------
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `knowledge_item` ADD COLUMN `publisher` VARCHAR(80) NULL COMMENT ''发布机构'' AFTER `content`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'knowledge_item' AND column_name = 'publisher'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `knowledge_item` ADD COLUMN `source_title` VARCHAR(180) NULL COMMENT ''来源标题'' AFTER `publisher`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'knowledge_item' AND column_name = 'source_title'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `knowledge_item` ADD COLUMN `source_url` VARCHAR(255) NULL COMMENT ''来源链接'' AFTER `source_title`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'knowledge_item' AND column_name = 'source_url'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `knowledge_item` ADD COLUMN `evidence_level` VARCHAR(4) NULL COMMENT ''A/B/C'' AFTER `source_url`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'knowledge_item' AND column_name = 'evidence_level'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 3. plans 增列：饮食目标与引用快照
-- ------------------------------------------------------------
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plans` ADD COLUMN `diet_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT ''本版是否包含饮食方案'' AFTER `ai_tokens`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'plans' AND column_name = 'diet_enabled'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plans` ADD COLUMN `daily_kcal_target` DOUBLE NULL COMMENT ''每日热量目标 kcal'' AFTER `diet_enabled`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'plans' AND column_name = 'daily_kcal_target'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plans` ADD COLUMN `protein_target` DOUBLE NULL COMMENT ''每日蛋白质目标 g'' AFTER `daily_kcal_target`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'plans' AND column_name = 'protein_target'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plans` ADD COLUMN `carb_target` DOUBLE NULL COMMENT ''每日碳水目标 g'' AFTER `protein_target`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'plans' AND column_name = 'carb_target'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plans` ADD COLUMN `fat_target` DOUBLE NULL COMMENT ''每日脂肪目标 g'' AFTER `carb_target`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'plans' AND column_name = 'fat_target'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plans` ADD COLUMN `references_json` LONGTEXT NULL COMMENT ''本版引用的来源清单（JSON）'' AFTER `fat_target`',
    'DO 0')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'plans' AND column_name = 'references_json'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 4. 动作投稿审核单
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `exercise_submission` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL COMMENT '投稿人',
  `username` VARCHAR(60) NULL,
  `name` VARCHAR(60) NOT NULL,
  `category` VARCHAR(20) NOT NULL,
  `muscle_group` VARCHAR(30) NOT NULL,
  `equipment` VARCHAR(20) NOT NULL,
  `difficulty` INT NOT NULL,
  `met_value` DOUBLE NOT NULL,
  `default_sets` INT NULL,
  `default_reps` VARCHAR(20) NULL,
  `description` VARCHAR(255) NULL,
  `contraindication` VARCHAR(100) NULL,
  `source_type` VARCHAR(24) NULL,
  `source_title` VARCHAR(180) NULL,
  `source_url` VARCHAR(255) NULL,
  `source_page` VARCHAR(64) NULL,
  `evidence_level` VARCHAR(4) NULL,
  `license_note` VARCHAR(120) NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
  `reviewer_id` BIGINT NULL,
  `reviewer_name` VARCHAR(60) NULL,
  `review_note` VARCHAR(255) NULL,
  `created_exercise_id` BIGINT NULL COMMENT '通过后生成的 exercises.id',
  `created_at` DATETIME NULL,
  `reviewed_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_exs_status` (`status`, `created_at`),
  KEY `idx_exs_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动作库投稿与审核流水';

-- ------------------------------------------------------------
-- 5. 食物库（带单位与来源；热量统一为 kcal/100g）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `food_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(60) NOT NULL,
  `alias` VARCHAR(80) NULL,
  `category` VARCHAR(20) NOT NULL COMMENT '主食/蛋白/蔬菜/水果/乳品/坚果/油脂/饮品',
  `kcal_per_100g` DOUBLE NOT NULL COMMENT '每 100 克可食部热量 kcal',
  `protein_g` DOUBLE NULL COMMENT '每 100g 蛋白质 g',
  `carb_g` DOUBLE NULL COMMENT '每 100g 碳水 g',
  `fat_g` DOUBLE NULL COMMENT '每 100g 脂肪 g',
  `fiber_g` DOUBLE NULL,
  `unit_label` VARCHAR(12) NOT NULL DEFAULT 'g' COMMENT '计量单位符号：g/个/碗/杯/片/勺',
  `household_measure` VARCHAR(32) NULL COMMENT '常见份量描述，如 1 碗米饭',
  `grams_per_unit` DOUBLE NULL COMMENT '1 个计量单位折合克数',
  `source_code` VARCHAR(24) NULL COMMENT 'CFCT6 / USDA / CNKI',
  `source_title` VARCHAR(180) NULL,
  `source_url` VARCHAR(255) NULL,
  `evidence_level` VARCHAR(4) NULL,
  `verified` TINYINT NOT NULL DEFAULT 1,
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_food_name` (`name`),
  KEY `idx_food_cat` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='食物成分库（含来源与单位）';

-- ------------------------------------------------------------
-- 6. 计划饮食明细
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `diet_plan_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `plan_id` BIGINT NOT NULL,
  `day_of_week` INT NOT NULL,
  `meal` VARCHAR(12) NOT NULL COMMENT '早餐/午餐/晚餐/加餐',
  `food_id` BIGINT NULL,
  `food_name` VARCHAR(60) NOT NULL,
  `grams` DOUBLE NOT NULL COMMENT '建议摄入克数',
  `unit_label` VARCHAR(12) NULL,
  `amount_desc` VARCHAR(48) NULL COMMENT '如 1 碗(150g)',
  `kcal` DOUBLE NOT NULL,
  `protein_g` DOUBLE NULL,
  `carb_g` DOUBLE NULL,
  `fat_g` DOUBLE NULL,
  `seq` INT NULL,
  `source_url` VARCHAR(255) NULL,
  PRIMARY KEY (`id`),
  KEY `idx_dpi_plan` (`plan_id`, `day_of_week`, `meal`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='计划附带的每日饮食安排';

-- ------------------------------------------------------------
-- 7. 回填：动作库来源（按动作类别映射到权威来源）
--    仅在 source_url 为空时回填，不覆盖人工填写。
-- ------------------------------------------------------------
UPDATE `exercises`
SET `source_type` = 'GUIDELINE',
    `source_title` = 'ACSM''s Guidelines for Exercise Testing and Prescription, 11th ed.',
    `source_url` = 'https://www.acsm.org/education-resources/books/guidelines-exercise-testing-prescription',
    `source_page` = 'Ch.7 Resistance Training',
    `evidence_level` = 'A',
    `license_note` = '引用指南结论，不复制原文图表',
    `last_verified_at` = '2026-11-15'
WHERE `source_url` IS NULL AND `category` = '力量';

UPDATE `exercises`
SET `source_type` = 'DATABASE',
    `source_title` = '2024 Adult Compendium of Physical Activities（MET 值来源）',
    `source_url` = 'https://pacompendium.com/',
    `source_page` = 'Activity categories 15xxx/17xxx',
    `evidence_level` = 'A',
    `license_note` = 'MET 值引用，CC BY 许可',
    `last_verified_at` = '2026-11-15'
WHERE `source_url` IS NULL AND `category` = '有氧';

UPDATE `exercises`
SET `source_type` = 'TEXTBOOK',
    `source_title` = 'NSCA Essentials of Strength Training and Conditioning, 4th ed.',
    `source_url` = 'https://www.nsca.com/education/books/',
    `source_page` = 'Ch.15 Exercise Technique',
    `evidence_level` = 'B',
    `license_note` = '引用技术要点，不复制原文',
    `last_verified_at` = '2026-11-15'
WHERE `source_url` IS NULL AND `category` = '核心';

UPDATE `exercises`
SET `source_type` = 'GUIDELINE',
    `source_title` = 'ACSM''s Guidelines for Exercise Testing and Prescription, 11th ed.（柔韧与热身章节）',
    `source_url` = 'https://www.acsm.org/education-resources/books/guidelines-exercise-testing-prescription',
    `source_page` = 'Ch.8 Flexibility',
    `evidence_level` = 'A',
    `license_note` = '引用指南结论',
    `last_verified_at` = '2026-11-15'
WHERE `source_url` IS NULL AND `category` = '柔韧';

UPDATE `exercises`
SET `source_type` = 'GUIDELINE',
    `source_title` = 'ACSM 高强度间歇训练（HIIT）立场声明与 2024 Compendium',
    `source_url` = 'https://pacompendium.com/',
    `source_page` = 'Vigorous-intensity categories',
    `evidence_level` = 'B',
    `license_note` = '引用结论与 MET 区间',
    `last_verified_at` = '2026-11-15'
WHERE `source_url` IS NULL AND `category` = 'HIIT';

-- 兜底：任何仍未填来源的动作（含历史投稿），标记为待核验
UPDATE `exercises`
SET `source_type` = 'COMMUNITY',
    `source_title` = '待补充来源',
    `source_url` = NULL,
    `evidence_level` = 'C',
    `review_status` = 'PENDING',
    `last_verified_at` = NULL
WHERE `source_url` IS NULL;

-- ------------------------------------------------------------
-- 8. 回填：知识条目来源（按 code 前缀映射）
-- ------------------------------------------------------------
UPDATE `knowledge_item` SET `publisher` = 'ACSM', `evidence_level` = 'A',
  `source_title` = 'ACSM''s Guidelines for Exercise Testing and Prescription, 11th ed.',
  `source_url` = 'https://www.acsm.org/education-resources/books/guidelines-exercise-testing-prescription'
WHERE `code` LIKE 'FITT-%' AND `source_url` IS NULL;

UPDATE `knowledge_item` SET `publisher` = 'ACSM', `evidence_level` = 'A',
  `source_title` = 'ACSM 运动测试与运动处方指南（心率与强度章节，Karvonen 法）',
  `source_url` = 'https://www.acsm.org/education-resources/books/guidelines-exercise-testing-prescription'
WHERE `code` LIKE 'HR-%' AND `source_url` IS NULL;

UPDATE `knowledge_item` SET `publisher` = 'ACSM', `evidence_level` = 'A',
  `source_title` = 'ACSM 渐进原则（Frequency/Intensity/Time 递增上限）',
  `source_url` = 'https://www.acsm.org/education-resources/books/guidelines-exercise-testing-prescription'
WHERE `code` LIKE 'PROG-%' AND `source_url` IS NULL;

UPDATE `knowledge_item` SET `publisher` = 'NSCA', `evidence_level` = 'B',
  `source_title` = 'NSCA Essentials of Strength Training and Conditioning（恢复与间隔安排）',
  `source_url` = 'https://www.nsca.com/education/books/'
WHERE `code` LIKE 'REC-%' AND `source_url` IS NULL;

UPDATE `knowledge_item` SET `publisher` = 'ACSM', `evidence_level` = 'A',
  `source_title` = 'ACSM 运动禁忌与风险分层（Contraindications）',
  `source_url` = 'https://www.acsm.org/education-resources/books/guidelines-exercise-testing-prescription'
WHERE `code` LIKE 'CI-%' AND `source_url` IS NULL;

UPDATE `knowledge_item` SET `publisher` = 'NSCA', `evidence_level` = 'B',
  `source_title` = 'NSCA 动作替代与技术要点',
  `source_url` = 'https://www.nsca.com/education/books/'
WHERE `code` LIKE 'SUB-%' AND `source_url` IS NULL;

UPDATE `knowledge_item` SET `publisher` = 'NSCA', `evidence_level` = 'B',
  `source_title` = 'NSCA 动作技术要点（Exercise Technique）',
  `source_url` = 'https://www.nsca.com/education/books/'
WHERE `code` LIKE 'TEC-%' AND `source_url` IS NULL;

UPDATE `knowledge_item` SET `publisher` = 'WHO', `evidence_level` = 'A',
  `source_title` = 'WHO Guidelines on Physical Activity and Sedentary Behaviour (2020)',
  `source_url` = 'https://www.who.int/publications/i/item/9789240015128'
WHERE `code` LIKE 'POP-%' AND `source_url` IS NULL;

UPDATE `knowledge_item`
SET `publisher` = COALESCE(`publisher`, 'FitLife 知识库'),
    `evidence_level` = COALESCE(`evidence_level`, 'C'),
    `source_title` = COALESCE(`source_title`, '站内运动处方条目'),
    `source_url` = COALESCE(`source_url`, 'https://www.who.int/publications/i/item/9789240015128')
WHERE `source_url` IS NULL;

-- ------------------------------------------------------------
-- 9. 食物库种子（热量单位统一 kcal / 100g；来源：中国食物成分表第6版 / USDA FDC）
-- ------------------------------------------------------------
INSERT IGNORE INTO `food_item`
(`name`,`alias`,`category`,`kcal_per_100g`,`protein_g`,`carb_g`,`fat_g`,`fiber_g`,`unit_label`,`household_measure`,`grams_per_unit`,`source_code`,`source_title`,`source_url`,`evidence_level`,`verified`,`enabled`,`created_at`) VALUES
('米饭(熟)','白米饭','主食',116,2.6,25.9,0.3,0.3,'碗','1 碗米饭',150,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('糙米饭(熟)','糙米','主食',112,2.6,23.0,0.9,1.8,'碗','1 碗糙米饭',150,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('馒头','白馒头','主食',223,7.0,47.0,1.1,1.3,'个','1 个馒头',100,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('全麦面包','吐司','主食',246,9.0,46.0,3.5,6.0,'片','1 片全麦面包',35,'USDA','USDA FoodData Central','https://fdc.nal.usda.gov/','A',1,1,NOW()),
('燕麦片(干)','燕麦','主食',377,15.0,66.2,6.7,5.3,'g','40g 一份',40,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('面条(熟)','煮面','主食',110,4.5,24.3,0.4,0.8,'碗','1 碗面条',200,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('红薯','地瓜','主食',86,1.6,20.1,0.2,1.6,'个','1 个中等红薯',150,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('玉米(鲜)','甜玉米','主食',112,4.0,22.8,1.2,2.9,'根','1 根玉米',200,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('土豆','马铃薯','主食',81,2.6,17.8,0.2,1.1,'个','1 个中等土豆',150,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('藜麦(熟)','奎奴亚藜','主食',120,4.4,21.3,1.9,2.8,'碗','1 碗熟藜麦',150,'USDA','USDA FoodData Central','https://fdc.nal.usda.gov/','A',1,1,NOW()),
('鸡蛋','水煮蛋','蛋白',144,13.3,2.8,8.8,0.0,'个','1 个鸡蛋(约50g)',50,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('鸡胸肉','鸡脯肉','蛋白',133,19.4,2.5,5.0,0.0,'g','100g 一块',100,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('鸡腿肉(去皮)','鸡腿','蛋白',146,20.0,0.0,7.2,0.0,'个','1 个鸡腿',90,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('牛肉(瘦)','牛里脊','蛋白',106,20.2,1.2,2.3,0.0,'g','100g 一份',100,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('猪里脊','瘦猪肉','蛋白',155,20.2,0.7,7.9,0.0,'g','100g 一份',100,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('三文鱼','鲑鱼','蛋白',139,17.2,0.0,7.8,0.0,'g','100g 一份',100,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('虾仁','对虾','蛋白',93,18.6,2.8,0.8,0.0,'g','100g 一份',100,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('金枪鱼罐头(水浸)','吞拿鱼','蛋白',116,25.5,0.0,0.8,0.0,'罐','1 罐(约120g)',120,'USDA','USDA FoodData Central','https://fdc.nal.usda.gov/','A',1,1,NOW()),
('北豆腐','老豆腐','蛋白',98,12.2,2.0,4.8,0.5,'块','1 块豆腐',100,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('豆浆(无糖)','豆奶','蛋白',31,3.0,1.6,1.6,0.0,'杯','1 杯(250ml)',250,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('蛋白粉(乳清)','乳清蛋白','蛋白',380,80.0,8.0,5.0,0.0,'勺','1 勺(约30g)',30,'USDA','USDA FoodData Central','https://fdc.nal.usda.gov/','A',1,1,NOW()),
('牛奶(全脂)','鲜奶','乳品',54,3.0,3.4,3.2,0.0,'杯','1 杯(250ml)',250,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('酸奶(原味)','发酵乳','乳品',72,2.5,9.3,2.7,0.0,'杯','1 杯(150g)',150,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('希腊酸奶(无糖)','浓缩酸奶','乳品',59,10.0,3.6,0.4,0.0,'杯','1 杯(150g)',150,'USDA','USDA FoodData Central','https://fdc.nal.usda.gov/','A',1,1,NOW()),
('西兰花','绿花菜','蔬菜',36,4.1,4.3,0.6,1.6,'g','200g 一份',200,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('菠菜','菠薐菜','蔬菜',28,2.6,4.5,0.3,1.7,'g','200g 一份',200,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('生菜','莴苣叶','蔬菜',15,1.3,2.0,0.3,0.7,'g','150g 一份',150,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('番茄','西红柿','蔬菜',20,0.9,4.0,0.2,0.5,'个','1 个番茄',150,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('黄瓜','青瓜','蔬菜',16,0.8,2.9,0.2,0.5,'根','1 根黄瓜',200,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('胡萝卜','红萝卜','蔬菜',39,1.0,8.8,0.2,1.1,'根','1 根胡萝卜',120,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('蘑菇(鲜)','白蘑菇','蔬菜',20,2.7,4.1,0.1,2.1,'g','150g 一份',150,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('苹果','红富士','水果',53,0.2,13.5,0.2,1.2,'个','1 个中等苹果',200,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('香蕉','甘蕉','水果',93,1.4,22.0,0.2,1.2,'根','1 根香蕉',120,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('橙子','柑橘','水果',48,0.8,11.1,0.2,0.6,'个','1 个橙子',200,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('蓝莓','越橘','水果',57,0.7,14.5,0.3,2.4,'g','100g 一份',100,'USDA','USDA FoodData Central','https://fdc.nal.usda.gov/','A',1,1,NOW()),
('猕猴桃','奇异果','水果',61,0.8,14.5,0.6,2.6,'个','1 个猕猴桃',80,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('核桃','胡桃','坚果',646,14.9,19.1,58.8,9.5,'g','3 个核桃(约30g)',30,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('花生(炒)','花生米','坚果',601,21.7,17.3,48.0,6.3,'g','20g 一小把',20,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('杏仁','巴旦木','坚果',578,21.2,21.7,49.9,12.5,'g','20g 一小把',20,'USDA','USDA FoodData Central','https://fdc.nal.usda.gov/','A',1,1,NOW()),
('橄榄油','初榨橄榄油','油脂',899,0.0,0.0,99.9,0.0,'勺','1 勺(10g)',10,'USDA','USDA FoodData Central','https://fdc.nal.usda.gov/','A',1,1,NOW()),
('花生油','食用油','油脂',899,0.0,0.0,99.9,0.0,'勺','1 勺(10g)',10,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('蜂蜜','蜜糖','饮品',321,0.4,75.6,1.9,0.0,'勺','1 勺(15g)',15,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','A',1,1,NOW()),
('黑咖啡(无糖)','美式咖啡','饮品',2,0.1,0.0,0.0,0.0,'杯','1 杯(240ml)',240,'USDA','USDA FoodData Central','https://fdc.nal.usda.gov/','A',1,1,NOW()),
('可乐','碳酸饮料','饮品',43,0.0,10.8,0.0,0.0,'罐','1 罐(330ml)',330,'USDA','USDA FoodData Central','https://fdc.nal.usda.gov/','A',1,1,NOW()),
('紫菜蛋花汤','紫菜汤','蔬菜',28,2.4,2.6,1.1,0.4,'碗','1 碗(250g)',250,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','B',1,1,NOW()),
('鸡胸肉沙拉(混合)','鸡肉沙拉','蛋白',105,9.8,4.2,5.2,1.4,'份','1 份(约250g)',250,'CFCT6','中国食物成分表 标准版 第6版','https://www.chinanutri.cn/','B',1,1,NOW()),
('全麦意面(熟)','意面','主食',124,5.3,26.5,0.5,3.2,'碗','1 碗(200g)',200,'USDA','USDA FoodData Central','https://fdc.nal.usda.gov/','A',1,1,NOW());

-- ------------------------------------------------------------
-- 10. 内容治理规则（审核阈值可配置，P5 新代码读取，缺失退内置）
-- ------------------------------------------------------------
INSERT IGNORE INTO `knowledge_rule`
(`code`,`name`,`category`,`expression`,`severity`,`enabled`,`version`,`note`,`updated_at`) VALUES
('CONTENT_REVIEW','动作库来源审核门槛','GOVERNANCE',
 '{"requireSourceUrl":true,"allowedDomains":["acsm.org","nsca.com","who.int","nih.gov","ncbi.nlm.nih.gov","fdc.nal.usda.gov","pacompendium.com","chinanutri.cn","exrx.net","doi.org"],"metRange":[1.0,20.0],"duplicateNameBlock":true,"minDescriptionLen":8,"autoApproveEvidenceLevel":"A"}',
 'WARN',1,1,'用户投稿动作的准入校验；autoApproveEvidenceLevel 仍需人工点通过','2026-11-15');

INSERT IGNORE INTO `knowledge_rule`
(`code`,`name`,`category`,`expression`,`severity`,`enabled`,`version`,`note`,`updated_at`) VALUES
('DIET_TARGET','饮食目标分配','NUTRITION',
 '{"proteinPerKg":{"fat_loss":[1.8,2.2],"muscle_gain":[1.6,2.0],"health":[1.2,1.6]},"fatRatio":0.25,"mealSplit":{"早餐":0.30,"午餐":0.40,"晚餐":0.30},"snackRatio":0.0,"vegGramsMin":150}',
 'INFO',1,1,'蛋白质按体重区间取中值；脂肪占总热量 25%；碳水为余量','2026-11-15');