-- ============================================================
-- P2 AI 主链路迁移：知识库 / 规则库 / Prompt 模板 / 调用计量 / 计划版本
--
-- 设计原则（与 V1 一致）
--   1. 幂等：可重复执行；建表 IF NOT EXISTS，种子 INSERT IGNORE，DDL 先查 information_schema；
--   2. 只做加法：plans 新增列全部可空或带默认值，老代码回滚后仍能跑；
--   3. MySQL 5.7 / 8.x / MariaDB 兼容：不用窗口函数，版本回填用用户变量法。
--
-- 执行方式
--   mysql -u root -p fitlife < sql/migration/V2__p2_ai_pipeline.sql
--   或由一键脚本 scripts/p2-oneclick.ps1 自动执行
-- ============================================================

-- ------------------------------------------------------------
-- 1. 知识条目表：运动处方领域的可检索知识（FITT、心率、进阶、恢复、禁忌、替代动作）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `knowledge_item` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `code`        VARCHAR(64)  NOT NULL COMMENT '条目编码，如 CI-KNEE',
  `type`        VARCHAR(24)  NOT NULL COMMENT 'FITT/HR/PROGRESSION/RECOVERY/CONTRAINDICATION/SUBSTITUTE/TECHNIQUE/POPULATION',
  `title`       VARCHAR(120) NOT NULL,
  `content`     TEXT         NOT NULL COMMENT '知识正文，直接注入 Prompt 约束清单',
  `goal_scope`  VARCHAR(60)  NULL COMMENT '适用目标，逗号分隔：fat_loss,muscle_gain,health；空=全部',
  `keywords`    VARCHAR(255) NULL COMMENT '检索关键词，逗号分隔（如 膝关节,深蹲）',
  `severity`    VARCHAR(12)  NOT NULL DEFAULT 'INFO' COMMENT 'INFO/WARN/ERROR，ERROR 级条目参与校验',
  `enabled`     TINYINT      NOT NULL DEFAULT 1,
  `version`     INT          NOT NULL DEFAULT 1,
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ki_code` (`code`),
  KEY `idx_ki_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运动处方知识条目';

-- ------------------------------------------------------------
-- 2. 规则表：六类校验器的可配置阈值（expression 为 JSON）
--    校验器读不到对应规则时退回内置默认值，保证规则表被清空也不会崩。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `knowledge_rule` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `code`       VARCHAR(64)  NOT NULL COMMENT 'HR_TARGET/FITT/PROGRESSION/RECOVERY/CONTRAINDICATION/CAPACITY',
  `name`       VARCHAR(120) NOT NULL,
  `category`   VARCHAR(32)  NOT NULL COMMENT '对应校验器编号 V1-V6',
  `expression` TEXT         NOT NULL COMMENT 'JSON 阈值参数',
  `severity`   VARCHAR(12)  NOT NULL DEFAULT 'ERROR' COMMENT '违反时报告的级别',
  `enabled`    TINYINT      NOT NULL DEFAULT 1,
  `version`    INT          NOT NULL DEFAULT 1,
  `note`       VARCHAR(255) NULL,
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kr_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运动处方校验规则';

-- ------------------------------------------------------------
-- 3. Prompt 模板表：版本化，可回滚，可对照实验
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `prompt_template` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `code`       VARCHAR(64)  NOT NULL COMMENT '模板编码，如 PLAN_GEN',
  `version`    INT          NOT NULL DEFAULT 1,
  `name`       VARCHAR(120) NOT NULL,
  `body`       LONGTEXT     NOT NULL COMMENT '模板正文，{{key}} 占位符由后端渲染',
  `active`     TINYINT      NOT NULL DEFAULT 0 COMMENT '同 code 只允许一个 active=1',
  `note`       VARCHAR(255) NULL,
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pt_code_version` (`code`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Prompt 模板版本';

-- ------------------------------------------------------------
-- 4. 大模型调用计量表：重试/转移/耗时/token 的落库证据
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ai_call_log` (
  `id`                BIGINT      NOT NULL AUTO_INCREMENT,
  `endpoint`          VARCHAR(32) NOT NULL COMMENT 'PLAN_GEN/CHAT/MATCH/DIET...',
  `user_id`           BIGINT      NULL,
  `model_id`          VARCHAR(64) NULL,
  `provider`          VARCHAR(32) NULL,
  `attempts`          INT         NOT NULL DEFAULT 1 COMMENT '同厂商内重试次数',
  `fallback_from`     VARCHAR(64) NULL COMMENT '跨厂商转移时记来源模型',
  `latency_ms`        INT         NULL,
  `prompt_tokens`     INT         NULL,
  `completion_tokens` INT         NULL,
  `total_tokens`      INT         NULL,
  `success`           TINYINT     NOT NULL DEFAULT 0,
  `error_class`       VARCHAR(64) NULL COMMENT 'TIMEOUT/HTTP_429/NO_MODEL/...',
  `created_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_log_created` (`created_at`),
  KEY `idx_ai_log_endpoint` (`endpoint`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大模型调用计量与审计';

-- ------------------------------------------------------------
-- 5. plans：版本链 + 校验报告 + 知识快照
-- ------------------------------------------------------------
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plans` ADD COLUMN `version` INT NOT NULL DEFAULT 1 COMMENT ''同用户内递增的版本号'' AFTER `status`',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'plans' AND COLUMN_NAME = 'version'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plans` ADD COLUMN `parent_id` BIGINT NULL COMMENT ''上一版本计划 id，回滚锚点'' AFTER `version`',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'plans' AND COLUMN_NAME = 'parent_id'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plans` ADD COLUMN `change_note` VARCHAR(512) NULL COMMENT ''本版变更说明（修复动作/降级原因）'' AFTER `parent_id`',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'plans' AND COLUMN_NAME = 'change_note'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plans` ADD COLUMN `validation_report` LONGTEXT NULL COMMENT ''规则校验报告 JSON'' AFTER `change_note`',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'plans' AND COLUMN_NAME = 'validation_report'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plans` ADD COLUMN `knowledge_snapshot` LONGTEXT NULL COMMENT ''生成时注入的知识条目/规则快照 JSON'' AFTER `validation_report`',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'plans' AND COLUMN_NAME = 'knowledge_snapshot'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plans` ADD COLUMN `source_link` VARCHAR(16) NULL COMMENT ''LLM/LLM_REPAIRED/TEMPLATE'' AFTER `knowledge_snapshot`',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'plans' AND COLUMN_NAME = 'source_link'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plans` ADD COLUMN `generation_ms` INT NULL COMMENT ''本次生成端到端耗时'' AFTER `source_link`',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'plans' AND COLUMN_NAME = 'generation_ms'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plans` ADD COLUMN `ai_tokens` INT NULL COMMENT ''本次生成消耗 token 总数'' AFTER `generation_ms`',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'plans' AND COLUMN_NAME = 'ai_tokens'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 历史数据回填：同用户按 id 升序编号 version（用户变量法，兼容 MySQL 5.7）
SET @rn := 0; SET @prev := -1;
UPDATE `plans` p
JOIN (
  SELECT `id`,
         @rn := IF(@prev = `user_id`, @rn + 1, 1) AS `ver`,
         @prev := `user_id` AS `_u`
  FROM `plans`
  ORDER BY `user_id`, `id`
) x ON p.`id` = x.`id`
SET p.`version` = x.`ver`;

-- 回填后才允许加唯一约束
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `plans` ADD UNIQUE KEY `uk_plans_user_version` (`user_id`, `version`)',
    'DO 0')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'plans' AND INDEX_NAME = 'uk_plans_user_version'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 6. user_profiles：静息心率（Karvonen 靶心率需要）
-- ------------------------------------------------------------
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `user_profiles` ADD COLUMN `hr_rest` INT NULL COMMENT ''静息心率 bpm，缺省按 70 估算''',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_profiles' AND COLUMN_NAME = 'hr_rest'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- 种子数据（INSERT IGNORE：uk 命中即跳过，可重复执行）
-- ============================================================

-- ---- 校验规则：六类校验器的阈值 ----
INSERT IGNORE INTO `knowledge_rule` (`code`, `name`, `category`, `expression`, `severity`, `note`) VALUES
('HR_TARGET', '靶心率区间', 'V1',
 '{"hrMaxFormula":"220-age","defaultHrRest":70,"moderatePct":[0.40,0.59],"vigorousPct":[0.60,0.85],"maxPct":0.90,"rpeToHrr":{"4":0.40,"5":0.50,"6":0.60,"7":0.70,"8":0.80,"9":0.88,"10":0.95}}',
 'ERROR', '有氧/HIIT 强度换算心率储备，超过 90% 判 ERROR；初级 RPE>=8 判 WARN'),
('FITT', 'FITT-VP 目标区间', 'V2',
 '{"fat_loss":{"days":[3,6],"cardioMinWeek":150,"rpe":[4,8]},"muscle_gain":{"days":[3,6],"rpe":[5,9],"majorMuscleFreqMin":2},"health":{"days":[2,6],"cardioMinWeek":120,"rpe":[4,8]}}',
 'WARN', '按目标核对训练频率、有氧周时长、强度区间、大肌群频次'),
('PROGRESSION', '循序渐进上限', 'V3',
 '{"maxWeeklyIncreasePct":10}',
 'ERROR', '有上一版本时，周训练容量（总组数）环比增幅不得超过 10%'),
('RECOVERY', '同肌群恢复间隔', 'V4',
 '{"minDayGap":2,"highIntensityRpe":8}',
 'WARN', '同一肌群两个训练日之间至少间隔 48 小时（相邻训练日视为违规）'),
('CONTRAINDICATION', '禁忌分级', 'V5',
 '{"matchField":"contraindication","errorSeverity":"ERROR"}',
 'ERROR', '计划内动作的禁忌人群字段命中用户伤病即 ERROR（候选过滤后的第二道防线）'),
('CAPACITY', '单次容量预算', 'V6',
 '{"itemsPerSession":[4,6],"sessionOverrunPct":15,"maxSetsPerMuscleWeek":20}',
 'WARN', '每次 4-6 个动作；预计时长不超预算 15%；单肌群周组数不超 20');

-- ---- 知识条目 ----
INSERT IGNORE INTO `knowledge_item` (`code`, `type`, `title`, `content`, `goal_scope`, `keywords`, `severity`) VALUES
('FITT-FAT','FITT','减脂 FITT-VP 处方','减脂目标：有氧 3-5 天/周、中等强度（心率储备 40-59%，RPE 5-6），每周有氧累计不低于 150 分钟；配合每周 2-3 次全身力量训练维持肌肉量。','fat_loss','减脂,有氧,FITT','ERROR'),
('FITT-MUSCLE','FITT','增肌 FITT-VP 处方','增肌目标：力量训练 3-6 天分化，每大肌群每周至少 2 次刺激、8-12 次 x 3-5 组、RPE 7-9；主要肌群不连续两天训练。','muscle_gain','增肌,分化,容量','ERROR'),
('FITT-HEALTH','FITT','保持健康 FITT-VP 处方','健康维持：每周 3-5 天混合训练（力量+有氧+柔韧），中等强度，每周累计 150 分钟中等强度有氧或 75 分钟高强度。','health,保持健康','健康,维持','INFO'),
('HR-MAX','HR','最大心率估算','HRmax ≈ 220 − 年龄（粗略估计，个体差异 ±10 bpm）。有条件时应以实测最大心率为准。','health,muscle_gain,fat_loss','最大心率,HRmax','INFO'),
('HR-KARVONEN','HR','Karvonen 靶心率公式','靶心率 = (HRmax − HRrest) × 强度% + HRrest。中等强度取 40-59% 心率储备，高强度取 60-85%；超过 90% 仅限高级人群间歇训练。','fat_loss,health,muscle_gain','靶心率,心率储备,Karvonen','ERROR'),
('HR-RPE','HR','RPE 与心率强度对照','RPE 自觉用力程度（1-10）与心率储备近似对应：4→40%、6→60%、8→80%、10→95%。谈话测试：中等强度可以说短句但不能唱歌。','health,fat_loss,muscle_gain','RPE,自觉强度,谈话测试','INFO'),
('PROG-10','PROGRESSION','每周进阶上限','每周训练容量（组数×次数或总吨位）增幅不超过上一周的 10%；出现关节疼痛、静息心率上升或睡眠变差时应先减量。','health,muscle_gain,fat_loss','进阶,10%,容量','ERROR'),
('PROG-LOAD','PROGRESSION','力量负荷递进','当某重量能以目标次数上限完成且 RPE ≤ 7 时，下次训练可加重 2.5-5%（上肢取小值）。','muscle_gain','加重,负荷,渐进超负荷','INFO'),
('REC-48','RECOVERY','同肌群恢复间隔','同一肌群两次高强度训练之间至少间隔 48 小时；大肌群（腿、背）恢复窗口可达 72 小时。','muscle_gain,health,fat_loss','恢复,48小时,连续训练','ERROR'),
('REC-SLEEP','RECOVERY','睡眠与恢复','训练日保证 7-9 小时睡眠；训练后 2 小时内补充蛋白质 20-40g。','health,muscle_gain,fat_loss','睡眠,恢复,蛋白质','INFO'),
('CI-KNEE','CONTRAINDICATION','膝关节禁忌','膝关节不适避免：深蹲（全幅度）、弓步蹲、跳绳、跳箱、腿屈伸大重量。优先选择：臀桥、直腿硬拉（轻）、靠墙静蹲（浅角度）、固定自行车。','health,fat_loss,muscle_gain','膝关节,深蹲,弓步,跳跃','ERROR'),
('CI-LUMBAR','CONTRAINDICATION','腰椎禁忌','腰椎不适避免：传统硬拉、仰卧起坐、早安式体前屈、负重转体。优先选择：平板支撑、侧桥、鸟狗式、死虫式。','health,fat_loss,muscle_gain','腰椎,仰卧起坐,硬拉,转体','ERROR'),
('CI-SHOULDER','CONTRAINDICATION','肩关节禁忌','肩关节不适避免：颈后推举、直立划船、宽握卧推、高幅侧平举。优先选择：中立握哑铃推举、小幅度侧平举、前平举。','health,muscle_gain','肩关节,推举,直立划船','ERROR'),
('CI-WRIST','CONTRAINDICATION','腕关节禁忌','腕关节不适避免：俯卧撑变体长时间背屈支撑、大重量腕弯举。可用中立握把、护腕或改哑铃中立握动作替代。','health','腕关节,俯卧撑','WARN'),
('CI-ANKLE','CONTRAINDICATION','踝关节禁忌','踝关节不适避免：跳绳、开合跳、深蹲跳、波比跳等高冲击动作。优先选择：固定自行车、椭圆机、游泳等低冲击有氧。','health,fat_loss','踝关节,跳跃,跳绳','ERROR'),
('CI-HTN','POPULATION','高血压人群注意','高血压人群：避免憋气（瓦式动作）、头低于心的倒立位、极限等长收缩；以中等强度动态有氧为主，RPE 不超过 6，训练前后测血压。','health,fat_loss','高血压,血压,憋气','ERROR'),
('SUB-KNEE','SUBSTITUTE','膝友好替代映射','深蹲→浅角度靠墙静蹲/臀桥/腿举（限幅度）；弓步→站姿髋外展/台阶踏步（低台）；跳绳→动感单车（低阻力）。','health,fat_loss,muscle_gain','膝关节,替代','ERROR'),
('SUB-LUMBAR','SUBSTITUTE','腰友好替代映射','硬拉→臀桥/轻负荷哑铃直腿罗马尼亚硬拉；仰卧起坐→平板支撑/死虫式；早安式→鸟狗式。','health,muscle_gain','腰椎,替代','ERROR'),
('SUB-SHOULDER','SUBSTITUTE','肩友好替代映射','颈后推举→中立握哑铃肩推（身前）；直立划船→小幅度侧平举；宽握卧推→窄距俯卧撑/哑铃地板卧推。','health,muscle_gain','肩关节,替代','ERROR'),
('SUB-HEART','SUBSTITUTE','心肺负荷降级映射','HIIT→稳态低强度有氧（心率 60% 以下）；跳箱→原地踏步；波比跳→无跳跃改良 burpee。','health,fat_loss','HIIT,替代,低冲击','WARN'),
('T-WARMUP','TECHNIQUE','热身规范','每次训练前 5-10 分钟热身：低强度有氧 + 动态拉伸，把心率提升到储备心率 50-60% 再进入正式组。','health,muscle_gain,fat_loss','热身,动态拉伸','INFO'),
('T-COOLDOWN','TECHNIQUE','放松与拉伸','训练后 5 分钟整理活动：静态拉伸每个部位 15-30 秒 × 2 组，配合呼吸放松。','health,muscle_gain,fat_loss','拉伸,放松,整理活动','INFO'),
('POP-ELDER','POPULATION','中老年人群','中老年用户：每周 2 次平衡训练（单腿站、脚跟走），力量用低负荷高次数（RPE 4-6），防跌倒优先。','health','中老年,平衡,防跌倒','WARN'),
('POP-DESK','POPULATION','久坐人群','久坐办公人群：每小时起身活动 2-3 分钟；训练重点为胸椎活动度、臀肌激活与圆肩矫正（面拉、YTW）。','health,fat_loss','久坐,办公,圆肩','INFO');

-- ---- Prompt 模板 v1（占位符由后端渲染；与代码内置模板同构，DB 缺失时代码兜底）----
INSERT IGNORE INTO `prompt_template` (`code`, `version`, `name`, `body`, `active`, `note`) VALUES
('PLAN_GEN', 1, '计划生成模板 v1',
'你是一名专业体能教练，需要为健身系统生成一周训练计划。\n\n【用户信息】\n- 健身目标：{{goal}}\n- 运动经验：{{experience}}\n- 每周训练天数：{{weeklyDays}} 天\n- 单次训练时长：{{sessionMinutes}} 分钟\n- 可用器械：{{equipments}}\n- 伤病史/禁忌部位：{{injuries}}\n- 体重：{{weightKg}} kg\n{{metrics}}\n【运动处方约束（必须全部满足，系统会逐条校验）】\n{{constraints}}\n【可选动作库】以下是系统中已审核的动作，你只能从中选择，禁止编造动作或使用列表外的动作名：\n{{candidates}}\n【编排要求】\n1. 只输出 JSON，不要任何解释文字、不要 markdown 代码块标记。\n2. 训练天数必须正好 {{weeklyDays}} 天，其余为休息日；休息日 items 为空数组。\n3. 每次训练包含 4-6 个动作：先做复合动作（力量），再补充孤立或核心动作。\n4. 如果是减脂目标，每个训练日末尾安排 1 个有氧动作；每周有氧总时长不低于 150 分钟。\n5. 如果是增肌目标，同一肌群每周至少安排 2 次刺激，主要肌群不要连续两天训练。\n6. 避免同一肌群连续两天高强度训练；初级用户每个动作 3 组，高级用户可 4-5 组。\n7. 组间休息：力量动作 60-120 秒，核心/柔韧 30-60 秒，有氧按分钟安排。\n8. 所有 exerciseId 必须严格取自上面动作库的 id 字段。\n9. summary 用 2-3 句中文说明计划思路与注意事项，并引用上面至少 2 条处方约束作为依据。\n\n【输出 JSON 结构】\n{\n  "title": "计划标题(中文，20字内)",\n  "summary": "计划思路说明",\n  "weeklyCardioMin": 150,\n  "days": [\n    {"dayOfWeek": 1, "focus": "胸 + 三头", "items": [\n      {"exerciseId": 6, "sets": 4, "reps": "8-12", "restSec": 90, "rpe": 7, "seq": 1}\n    ]},\n    {"dayOfWeek": 2, "focus": "休息", "items": []}\n  ]\n}',
1, 'P2 初版：在 P1 内联 Prompt 基础上加入身体指标与处方约束注入位');
