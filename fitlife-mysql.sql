-- ============================================================
-- FitLife 数据库初始化 / 升级脚本 v4（幂等，可反复执行且不清空业务数据）
--
-- 变更要点：
--   1. 不再删除重建数据库，改用 CREATE DATABASE / TABLE IF NOT EXISTS
--   2. 自动为已存在的 user_profiles 补齐训练画像字段
--   3. 新增 exercises 动作库（100 条已审核动作）与 plans / plan_details
--   4. 种子数据用 INSERT IGNORE，重复执行不会产生重复记录
--   5. 账号、饮食、运动、体重等业务数据完整保留
--   6. P1：users 增加 role/status/last_login_at；daily_records 补 (user_id,date) 唯一约束；
--      明细表补 (user_id,date) 索引；新增 idempotency_key、audit_log 两张表
--
-- 用法：mysql -u root -p --default-character-set=utf8mb4 < fitlife-mysql.sql
-- 全量重建：先执行 sql/fitlife-reset.sql，再执行本脚本
-- 增量升级：sql/migration/V1__p1_hardening.sql（只做 P1 那部分，可单独执行）
-- ============================================================

CREATE DATABASE IF NOT EXISTS `fitlife` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `fitlife`;

SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------- 用户表 ----------------------------
CREATE TABLE IF NOT EXISTS `users` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `created_at` DATETIME NULL,
  `password` VARCHAR(255) NOT NULL,
  `username` VARCHAR(255) NOT NULL,
  `role` VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT 'USER / ADMIN',
  `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  `last_login_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_username` (`username`),
  KEY `idx_users_role` (`role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------- 健康档案表 ----------------------------
CREATE TABLE IF NOT EXISTS `user_profiles` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `activity_level` VARCHAR(255) NULL,
  `age` INT NULL,
  `body_fat` DOUBLE NULL,
  `gender` VARCHAR(255) NULL,
  `goal` VARCHAR(255) NULL,
  `height` DOUBLE NULL,
  `name` VARCHAR(255) NULL,
  `user_id` BIGINT NOT NULL,
  `weight` DOUBLE NULL,
  `experience` VARCHAR(20) NULL COMMENT '运动经验：初级/中级/高级',
  `weekly_days` INT NULL COMMENT '每周可训练天数',
  `session_minutes` INT NULL COMMENT '单次训练时长(分钟)',
  `equipments` VARCHAR(120) NULL COMMENT '可用器械,逗号分隔',
  `injuries` VARCHAR(120) NULL COMMENT '伤病史/禁忌部位,逗号分隔',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_profiles_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 旧库平滑升级：仅当字段不存在时才添加，可重复执行
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_profiles' AND COLUMN_NAME = 'experience');
SET @ddl = IF(@c = 0, 'ALTER TABLE `user_profiles` ADD COLUMN `experience` VARCHAR(20) NULL', 'DO 0');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_profiles' AND COLUMN_NAME = 'weekly_days');
SET @ddl = IF(@c = 0, 'ALTER TABLE `user_profiles` ADD COLUMN `weekly_days` INT NULL', 'DO 0');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_profiles' AND COLUMN_NAME = 'session_minutes');
SET @ddl = IF(@c = 0, 'ALTER TABLE `user_profiles` ADD COLUMN `session_minutes` INT NULL', 'DO 0');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_profiles' AND COLUMN_NAME = 'equipments');
SET @ddl = IF(@c = 0, 'ALTER TABLE `user_profiles` ADD COLUMN `equipments` VARCHAR(120) NULL', 'DO 0');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_profiles' AND COLUMN_NAME = 'injuries');
SET @ddl = IF(@c = 0, 'ALTER TABLE `user_profiles` ADD COLUMN `injuries` VARCHAR(120) NULL', 'DO 0');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------- users 表 P1 升级（角色 / 状态 / 最近登录） ----------------------------
-- 旧库没有这三列，逐列判断后再加，可重复执行
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'role');
SET @ddl = IF(@c = 0, 'ALTER TABLE `users` ADD COLUMN `role` VARCHAR(20) NOT NULL DEFAULT ''USER'' COMMENT ''USER / ADMIN'' AFTER `password`', 'DO 0');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'status');
SET @ddl = IF(@c = 0, 'ALTER TABLE `users` ADD COLUMN `status` VARCHAR(20) NOT NULL DEFAULT ''ACTIVE'' COMMENT ''ACTIVE / DISABLED'' AFTER `role`', 'DO 0');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'last_login_at');
SET @ddl = IF(@c = 0, 'ALTER TABLE `users` ADD COLUMN `last_login_at` DATETIME NULL AFTER `created_at`', 'DO 0');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c = (SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND INDEX_NAME = 'idx_users_role');
SET @ddl = IF(@c = 0, 'ALTER TABLE `users` ADD INDEX `idx_users_role` (`role`)', 'DO 0');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------------------------- 业务记录表 ----------------------------
CREATE TABLE IF NOT EXISTS `custom_foods` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `calories` DOUBLE NOT NULL,
  `carbs` DOUBLE NULL,
  `category` VARCHAR(255) NULL,
  `fat` DOUBLE NULL,
  `name` VARCHAR(255) NOT NULL,
  `protein` DOUBLE NULL,
  `serving_size` DOUBLE NULL,
  `unit` VARCHAR(255) NULL,
  `user_id` BIGINT NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `daily_records` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `date` DATE NOT NULL,
  `exercise_burn` DOUBLE NULL,
  `food_intake` DOUBLE NULL,
  `target_calories` DOUBLE NULL,
  `user_id` BIGINT NOT NULL,
  `weight` DOUBLE NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_daily_user_date` (`user_id`,`date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `food_intakes` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `amount` DOUBLE NULL,
  `calories` DOUBLE NOT NULL,
  `date` DATE NOT NULL,
  `food_id` VARCHAR(255) NULL,
  `user_id` BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_food_user_date` (`user_id`,`date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `workout_records` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `calories_burned` DOUBLE NULL,
  `date` DATE NOT NULL,
  `duration` INT NOT NULL,
  `exercise_id` VARCHAR(255) NOT NULL,
  `exercise_name` VARCHAR(255) NULL,
  `user_id` BIGINT NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_workout_user_date` (`user_id`,`date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------- 记录表 P1 升级（唯一约束与索引） ----------------------------
-- 旧库 daily_records 没有 (user_id, date) 唯一约束：并发首插会生成两条当天记录，
-- 之后回写只更新其中一条，统计就会漂。先去重（保留 id 最大的一条）再加唯一键。
DELETE d1 FROM `daily_records` d1
  JOIN `daily_records` d2
    ON d1.`user_id` = d2.`user_id`
   AND d1.`date`    = d2.`date`
   AND d1.`id` < d2.`id`;

SET @c = (SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'daily_records' AND INDEX_NAME = 'uk_daily_user_date');
SET @ddl = IF(@c = 0, 'ALTER TABLE `daily_records` ADD UNIQUE KEY `uk_daily_user_date` (`user_id`,`date`)', 'DO 0');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c = (SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'food_intakes' AND INDEX_NAME = 'idx_food_user_date');
SET @ddl = IF(@c = 0, 'ALTER TABLE `food_intakes` ADD INDEX `idx_food_user_date` (`user_id`,`date`)', 'DO 0');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @c = (SELECT COUNT(*) FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workout_records' AND INDEX_NAME = 'idx_workout_user_date');
SET @ddl = IF(@c = 0, 'ALTER TABLE `workout_records` ADD INDEX `idx_workout_user_date` (`user_id`,`date`)', 'DO 0');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

-- 注意：plans 表在本段之后才创建，索引直接写在建表语句里（旧库由 sql/migration 补）

-- ---------------------------- 幂等键表 ----------------------------
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
  UNIQUE KEY `uk_idem_user_key` (`user_id`,`idem_key`),
  KEY `idx_idem_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口幂等键';

-- ---------------------------- 审计日志表 ----------------------------
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

-- ---------------------------- 运动动作库 ----------------------------
CREATE TABLE IF NOT EXISTS `exercises` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(60) NOT NULL COMMENT '动作名称',
  `category` VARCHAR(20) NOT NULL COMMENT '力量/有氧/核心/柔韧/HIIT',
  `muscle_group` VARCHAR(30) NOT NULL COMMENT '胸/背/肩/手臂/腿/臀/核心/全身',
  `equipment` VARCHAR(20) NOT NULL COMMENT '徒手/哑铃/杠铃/器械/弹力带/壶铃',
  `difficulty` TINYINT NOT NULL DEFAULT 1 COMMENT '1初级 2中级 3高级',
  `met_value` DECIMAL(3,1) NOT NULL COMMENT '代谢当量',
  `default_sets` INT DEFAULT 3 COMMENT '默认组数',
  `default_reps` VARCHAR(20) DEFAULT NULL COMMENT '默认次数/时长',
  `description` VARCHAR(255) DEFAULT NULL COMMENT '动作要领',
  `contraindication` VARCHAR(100) DEFAULT NULL COMMENT '禁忌症人群',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ex_name` (`name`),
  KEY `idx_ex_category` (`category`),
  KEY `idx_ex_muscle` (`muscle_group`),
  KEY `idx_ex_equip` (`equipment`),
  KEY `idx_ex_diff` (`difficulty`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运动动作库';

-- ---------------------------- 训练计划表 ----------------------------
CREATE TABLE IF NOT EXISTS `plans` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `title` VARCHAR(120) NULL,
  `goal` VARCHAR(30) NULL,
  `weekly_days` INT NULL,
  `session_minutes` INT NULL,
  `summary` TEXT NULL,
  `weekly_cardio_min` INT NULL,
  `weekly_calories` DOUBLE NULL,
  `ai_generated` TINYINT(1) DEFAULT 1 COMMENT '1=大模型生成 0=规则模板兜底',
  `status` VARCHAR(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED',
  `created_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_plans_user` (`user_id`),
  KEY `idx_plans_status` (`user_id`,`status`),
  KEY `idx_plans_user_created` (`user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='训练计划主表';

CREATE TABLE IF NOT EXISTS `plan_details` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `plan_id` BIGINT NOT NULL,
  `day_of_week` INT NOT NULL COMMENT '1-7',
  `day_focus` VARCHAR(60) NULL COMMENT '当天训练重点',
  `exercise_id` BIGINT NULL,
  `exercise_name` VARCHAR(60) NULL,
  `muscle_group` VARCHAR(30) NULL,
  `sets` INT NULL,
  `reps` VARCHAR(20) NULL,
  `rest_sec` INT NULL,
  `rpe` INT NULL COMMENT '主观强度1-10',
  `seq` INT NULL COMMENT '当天动作顺序',
  `est_calories` DOUBLE NULL COMMENT '估算热量kcal',
  PRIMARY KEY (`id`),
  KEY `idx_pd_plan` (`plan_id`,`day_of_week`,`seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='训练计划明细表';

-- ---------------------------- 默认演示账号 ----------------------------
-- 仅当账号不存在时插入；密码为 123456 的 BCrypt 值
INSERT IGNORE INTO `users` (`id`,`created_at`,`password`,`username`,`role`,`status`) VALUES
(1, NOW(), '$2a$10$.wbDFroaC3lbuIxpzkTDMumvt75BVdvuRGZCYNtZ87eDj4oYPdTgK', 'dandan', 'ADMIN', 'ACTIVE');

-- 老库里 dandan 已经存在（INSERT IGNORE 不会改），单独把角色补成管理员，后台管理才有入口
UPDATE `users` SET `role` = 'ADMIN' WHERE `username` = 'dandan';

INSERT IGNORE INTO `user_profiles`
  (`id`,`activity_level`,`age`,`body_fat`,`gender`,`goal`,`height`,`name`,`user_id`,`weight`,
   `experience`,`weekly_days`,`session_minutes`,`equipments`,`injuries`) VALUES
(1,'moderate',25,25.0,'female','fat_loss',165.0,'',1,60.0,'初级',3,45,'徒手','');

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 动作库种子数据（100 条，幂等）
-- ============================================================

-- 清理历史重复动作（保留最小 id，避免计划明细引用的动作 id 失效）
DELETE e1 FROM `exercises` e1
  JOIN `exercises` e2 ON e1.`name` = e2.`name` AND e1.`id` > e2.`id`;

-- 若旧表缺少唯一索引则补建
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'exercises' AND INDEX_NAME = 'uk_ex_name');
SET @ddl = IF(@idx_exists = 0,
              'ALTER TABLE `exercises` ADD UNIQUE KEY `uk_ex_name` (`name`)',
              'DO 0');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

INSERT IGNORE INTO `exercises`
  (`name`,`category`,`muscle_group`,`equipment`,`difficulty`,`met_value`,
   `default_sets`,`default_reps`,`description`,`contraindication`) VALUES
('俯卧撑','力量','胸','徒手','1','3.8','3','10-15','手掌略宽于肩，身体保持一条直线，胸部贴近地面后推起',''),
('跪姿俯卧撑','力量','胸','徒手','1','3.5','3','12-15','膝盖着地，减小负荷，适合初学者建立推力基础',''),
('宽距俯卧撑','力量','胸','徒手','2','4.0','3','10-12','双手距离约为肩宽1.5倍，强化胸大肌外侧','肩关节'),
('上斜俯卧撑','力量','胸','徒手','1','3.5','3','12-15','双手撑于台面，身体下斜，降低动作难度',''),
('下斜俯卧撑','力量','胸','徒手','3','4.5','3','8-12','双脚抬高，重点刺激胸大肌上部','肩关节'),
('哑铃卧推','力量','胸','哑铃','2','5.0','4','8-12','仰卧推起哑铃，肘部约45度外展，控制下放速度','肩关节'),
('哑铃飞鸟','力量','胸','哑铃','2','4.0','3','10-15','微屈肘向两侧打开，感受胸部拉伸，勿过度下沉','肩关节'),
('杠铃卧推','力量','胸','杠铃','3','6.0','4','6-10','复合动作，全程有保护，肩胛骨收紧下沉','肩关节'),
('上斜杠铃卧推','力量','胸','杠铃','3','6.0','4','6-10','倾角30-45度，强化上胸与三角肌前束','肩关节'),
('器械推胸','力量','胸','器械','1','4.5','3','10-15','轨迹固定，适合新手学习胸部发力模式',''),
('蝴蝶机夹胸','力量','胸','器械','1','4.0','3','12-15','孤立胸大肌，肘部保持微屈，顶峰收缩',''),
('双杠臂屈伸','力量','胸','徒手','3','5.5','3','6-12','身体前倾偏重胸部，下沉至肩略低于肘','肩关节'),
('引体向上','力量','背','徒手','3','6.0','4','5-10','垂直拉动作，肩胛先下沉再屈肘上拉','肩关节'),
('弹力带辅助引体','力量','背','弹力带','2','5.5','3','6-10','弹力带提供助力，帮助完成标准引体轨迹','肩关节'),
('高位下拉','力量','背','器械','1','4.5','3','10-12','拉至锁骨位置，避免身体后仰借力',''),
('坐姿划船','力量','背','器械','1','4.5','3','10-12','水平拉动作，肩胛骨向中间收拢',''),
('杠铃划船','力量','背','杠铃','3','6.0','4','6-10','俯身约45度，拉至腹部，保持腰椎中立','腰椎'),
('单臂哑铃划船','力量','背','哑铃','2','5.0','3','10-12','单手撑凳，另一侧拉至髋部，纠正左右不平衡','腰椎'),
('山羊挺身','力量','背','器械','2','4.0','3','12-15','强化竖脊肌与臀大肌，起身勿过度超伸','腰椎'),
('直臂下压','力量','背','器械','1','3.5','3','12-15','孤立背阔肌，手臂保持微屈下压',''),
('弹力带划船','力量','背','弹力带','1','4.0','3','12-15','居家替代划船动作，阻力可调',''),
('超人式','力量','背','徒手','1','3.0','3','10-15','俯卧同时抬起四肢，强化下背与臀肌','腰椎'),
('哑铃肩推','力量','肩','哑铃','2','5.0','4','8-12','坐姿推举，核心收紧，勿过度挺腰','肩关节'),
('杠铃肩推','力量','肩','杠铃','3','6.0','4','6-10','站姿推举，全程控制，避免腰部代偿','肩关节,腰椎'),
('哑铃侧平举','力量','肩','哑铃','1','3.5','3','12-15','孤立三角肌中束，抬至肩高即可','肩关节'),
('哑铃前平举','力量','肩','哑铃','1','3.5','3','12-15','刺激三角肌前束，避免耸肩','肩关节'),
('俯身侧平举','力量','肩','哑铃','2','3.5','3','12-15','俯身向两侧抬起，强化三角肌后束','腰椎'),
('面拉','力量','肩','弹力带','1','3.5','3','15','拉向面部并外旋，改善圆肩与肩关节健康',''),
('阿诺德推举','力量','肩','哑铃','3','5.0','3','8-12','推举过程加入旋转，覆盖三角肌三个束','肩关节'),
('哑铃弯举','力量','手臂','哑铃','1','3.5','3','10-15','孤立肱二头肌，肘部固定贴身',''),
('杠铃弯举','力量','手臂','杠铃','2','4.0','3','8-12','双手同握，避免身体前后摆动借力','腕关节'),
('锤式弯举','力量','手臂','哑铃','1','3.5','3','10-15','掌心相对，兼练肱肌与肱桡肌',''),
('窄距俯卧撑','力量','手臂','徒手','2','4.0','3','10-12','双手窄于肩，强化肱三头肌','腕关节'),
('绳索下压','力量','手臂','器械','1','3.5','3','12-15','肘部贴身固定，仅前臂发力下压',''),
('仰卧臂屈伸','力量','手臂','哑铃','2','4.0','3','10-12','仰卧屈肘下放至额头上方，再伸直',''),
('凳上反屈伸','力量','手臂','徒手','2','4.0','3','10-15','背对凳子撑起身体，下沉至上臂平行地面','腕关节,肩关节'),
('徒手深蹲','力量','腿','徒手','1','5.0','3','15-20','屈髋屈膝下蹲至大腿接近平行，膝盖对准脚尖',''),
('杠铃深蹲','力量','腿','杠铃','3','7.0','4','5-8','下肢复合之王，全程核心收紧、腰背中立','膝关节,腰椎'),
('哑铃高脚杯深蹲','力量','腿','哑铃','2','5.5','3','10-12','胸前持铃有助于保持躯干直立',''),
('箭步蹲','力量','腿','徒手','2','5.0','3','10-12','前后分腿下蹲，前膝不超过脚尖过多','膝关节'),
('哑铃箭步蹲','力量','腿','哑铃','2','5.5','3','10-12','双手持铃增加负荷，注意重心稳定','膝关节'),
('保加利亚分腿蹲','力量','腿','哑铃','3','6.0','3','8-12','后脚搭高，单腿承重，强度较高','膝关节'),
('腿举','力量','腿','器械','1','5.0','4','10-15','背部贴紧靠垫，膝盖不锁死','膝关节'),
('腿屈伸','力量','腿','器械','1','4.0','3','12-15','孤立股四头肌，控制离心阶段','膝关节'),
('腿弯举','力量','腿','器械','1','4.0','3','12-15','孤立腘绳肌，避免腰部抬离',''),
('罗马尼亚硬拉','力量','腿','杠铃','3','6.0','4','8-10','屈髋后坐，感受腘绳肌拉伸，背部保持中立','腰椎'),
('臀桥','力量','臀','徒手','1','3.5','3','15-20','仰卧屈膝顶髋，顶端夹紧臀部',''),
('杠铃臀推','力量','臀','杠铃','2','5.5','4','10-12','上背靠凳，髋部发力顶起杠铃','腰椎'),
('弹力带侧步走','力量','臀','弹力带','1','4.0','3','15-20','半蹲位横向移动，强化臀中肌',''),
('站姿提踵','力量','腿','徒手','1','3.0','3','20-25','踮起脚跟顶峰停顿，强化小腿三头肌',''),
('靠墙静蹲','力量','腿','徒手','1','4.0','3','45-60','背靠墙保持半蹲，时间计量，强化股四头肌耐力','膝关节'),
('徒手早安式','力量','腿','徒手','2','4.0','3','12-15','屈髋前倾再起身，激活臀腿后链','腰椎'),
('壶铃摆荡','力量','全身','壶铃','3','9.0','4','15-20','髋部铰链发力甩起壶铃，兼具力量与心肺','腰椎'),
('平板支撑','核心','核心','徒手','1','3.0','3','30-60','肘撑地保持躯干成直线，核心全程收紧',''),
('侧平板支撑','核心','核心','徒手','2','3.0','3','30-45','单侧肘撑，强化腹斜肌与侧链稳定','肩关节'),
('卷腹','核心','核心','徒手','1','3.0','3','15-20','仅抬起肩胛离地，避免颈部发力','腰椎'),
('仰卧抬腿','核心','核心','徒手','2','3.5','3','12-15','下腹主导，腰部贴地不悬空','腰椎'),
('俄罗斯转体','核心','核心','徒手','2','4.0','3','20-30','坐姿左右转体，强化腹斜肌','腰椎'),
('死虫式','核心','核心','徒手','1','2.5','3','12-16','仰卧对侧手脚交替伸展，腰部始终贴地',''),
('鸟狗式','核心','核心','徒手','1','2.5','3','12-16','四点支撑对侧手脚伸展，训练核心稳定',''),
('登山者','核心','核心','徒手','2','8.0','3','30-45','平板位交替提膝，兼顾核心与心肺','腕关节'),
('悬垂举腿','核心','核心','徒手','3','4.5','3','8-12','悬垂屈髋抬腿，进阶腹肌动作','肩关节,腰椎'),
('健腹轮','核心','核心','器械','3','5.0','3','8-12','跪姿前推再收回，核心全程抗伸展','腰椎,肩关节'),
('仰卧起坐','核心','核心','徒手','1','3.5','3','15-20','传统腹肌动作，注意避免颈部拉扯','腰椎'),
('仰卧屈膝收腹','核心','核心','徒手','1','3.0','3','15-20','屈膝收腹，动作幅度小、腰部安全',''),
('快走','有氧','全身','徒手','1','4.0','1','30-60分钟','低强度持续有氧，关节压力小，适合基础人群',''),
('慢跑','有氧','全身','徒手','1','7.0','1','20-45分钟','中等强度有氧，注意配速与落地缓冲','膝关节'),
('室内动感单车','有氧','腿','器械','1','7.0','1','20-40分钟','低冲击有氧，座椅高度需调整合适',''),
('椭圆机','有氧','全身','器械','1','5.0','1','20-40分钟','关节友好型有氧，可调阻力',''),
('划船机','有氧','全身','器械','2','7.0','1','15-30分钟','全身参与的有氧，注意腿部先发力','腰椎'),
('跳绳','有氧','全身','徒手','2','10.0','1','10-20分钟','高效燃脂，需良好缓冲与节奏控制','膝关节,腕关节'),
('爬楼梯','有氧','腿','徒手','1','8.0','1','15-30分钟','垂直方向有氧，强化下肢与心肺','膝关节'),
('开合跳','有氧','全身','徒手','1','8.0','1','10-15分钟','全身热身与燃脂动作，落地轻缓','膝关节'),
('原地高抬腿','有氧','全身','徒手','1','8.0','1','10-15分钟','快速提膝，提升心率，可分段进行','膝关节'),
('游泳自由泳','有氧','全身','徒手','2','8.0','1','20-40分钟','全身低冲击有氧，对关节压力极小','肩关节'),
('登山机','有氧','全身','器械','1','8.0','1','15-30分钟','模拟爬坡，下肢与心肺双重刺激','膝关节'),
('有氧操','有氧','全身','徒手','1','6.5','1','20-40分钟','节奏化全身运动，趣味性强易坚持',''),
('站姿体前屈','柔韧','全身','徒手','1','2.3','1','30秒×2','放松腘绳肌与下背，膝盖可微屈','腰椎'),
('猫牛式','柔韧','背','徒手','1','2.0','1','30秒×2','四点支撑交替拱背塌腰，活动脊柱',''),
('婴儿式','柔韧','背','徒手','1','1.5','1','60秒','跪坐前趴，放松腰背与肩部',''),
('蝴蝶式拉伸','柔韧','腿','徒手','1','2.0','1','45秒×2','脚掌相对坐姿，拉伸大腿内侧',''),
('髋屈肌拉伸','柔韧','腿','徒手','1','2.0','1','30秒×2','弓步位下压髋部，缓解久坐紧张',''),
('胸部门框拉伸','柔韧','胸','徒手','1','2.0','1','30秒×2','前臂扶门框，身体前倾拉伸胸大肌','肩关节'),
('肩部绕环','柔韧','肩','徒手','1','2.5','1','20次×2','肩关节前后绕环，热身与放松兼顾',''),
('腘绳肌拉伸','柔韧','腿','徒手','1','2.0','1','30秒×2','单腿伸直前压，感受大腿后侧牵拉',''),
('小腿拉伸','柔韧','腿','徒手','1','2.0','1','30秒×2','弓步推墙，后脚跟踩实地面',''),
('眼镜蛇式','柔韧','背','徒手','1','2.0','1','30秒×2','俯卧撑起上身，拉伸腹部与髋前侧','腰椎'),
('鸽子式','柔韧','臀','徒手','2','2.0','1','45秒×2','单腿前屈外展，深层拉伸臀部','膝关节'),
('波比跳','HIIT','全身','徒手','3','10.0','4','30秒','俯撑-收腿-起跳连贯完成，全身高强度','膝关节,腰椎'),
('战绳','HIIT','全身','器械','3','10.0','4','30秒','快速交替甩绳，上肢与核心高强度输出','肩关节'),
('壶铃深蹲推举','HIIT','全身','壶铃','3','8.0','4','30秒','深蹲起身顺势推举，力量与心肺结合','肩关节,腰椎'),
('哑铃快速划船','HIIT','背','哑铃','2','7.0','4','30秒','快节奏划船，提升代谢率','腰椎'),
('跳箱','HIIT','腿','器械','3','8.5','4','30秒','爆发式跳跃上箱，落地需屈髋缓冲','膝关节'),
('冲刺跑','HIIT','全身','徒手','3','12.0','6','20秒','全力短距离冲刺，间歇充分休息','膝关节'),
('平板开合跳','HIIT','核心','徒手','2','8.0','4','30秒','平板支撑位双腿开合，核心持续抗旋','腕关节'),
('哑铃复合推举','HIIT','全身','哑铃','2','7.0','4','30秒','深蹲接推举的复合动作，全身参与','肩关节'),
('箭步跳','HIIT','腿','徒手','3','9.0','4','30秒','交替跳跃箭步，下肢爆发与心肺','膝关节'),
('弹力带深蹲','HIIT','腿','弹力带','1','5.0','3','15-20','弹力带增加阻力，蹲起节奏可控',''),
('哑铃农夫行走','HIIT','全身','哑铃','2','6.0','3','40秒','双手持铃行走，强化握力与核心稳定',''),
('侧向弓步','HIIT','腿','徒手','1','4.5','3','12-15','横向跨步下蹲，强化内收肌与臀中肌','膝关节');

SELECT COUNT(*) AS exercise_count FROM `exercises`;

-- ============================================================
-- 导入结果校验
-- ============================================================
SELECT (SELECT COUNT(*) FROM `exercises`)      AS exercise_count,
       (SELECT COUNT(*) FROM `plans`)          AS plan_count,
       (SELECT COUNT(*) FROM `users`)          AS user_count;
