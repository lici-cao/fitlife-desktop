-- ============================================================
-- 危险操作：清空整个 fitlife 数据库（仅 -ResetDb 时由一键脚本调用）
-- 执行后请紧接着执行 fitlife-mysql.sql 重新建库
-- 用法：mysql -u root -p < sql/fitlife-reset.sql
-- ============================================================
DROP DATABASE IF EXISTS `fitlife`;
