-- ============================================================
-- 03 增量迁移：把 user 表补齐成「学号 + 密码」可用的状态
-- 执行顺序：在 01_schema.sql 之后
--
-- 【什么时候需要跑】
--   库里已经建过 user 表，但缺 student_no / password 列，或缺 student_no 的唯一索引。
--   01_schema.sql 用的是 CREATE TABLE IF NOT EXISTS —— 表已存在时它一行都不改，
--   所以老库只能靠本文件升级。
--   全新环境直接跑 01_schema.sql 就够了，本文件跑了也无害。
--
-- 【本文件是幂等的】每一步都先查 information_schema 再决定做不做，反复执行不报错。
--
-- 注：本文件不负责 openid 列。它已于 2026-10-08 从库里删除
--（trae/my 那一端的 User 实体也同步去掉了这个字段，所以删列是安全的）。
-- 本文件只做三件事：student_no 列、password 列、student_no 唯一索引。
-- ============================================================

USE
library_seat;

SET
@tbl := (SELECT COUNT(*) FROM information_schema.TABLES
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user');

SET
@has_student_no := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'student_no');

SET
@has_password := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'password');

SET
@uk_student_no := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND INDEX_NAME = 'student_no');

-- ------------------------------------------------------------
-- 1. student_no 列
-- ------------------------------------------------------------
-- 建成可空而不是 NOT NULL：老库里可能已经有历史账号行（比如微信登录时代建的），
-- 给一个没有默认值的 NOT NULL 新列会让 ALTER 直接失败。本端注册时必定写入它，
-- 所以"可空"只影响历史行，不影响新数据。
SET
@sql := IF(@tbl > 0 AND @has_student_no = 0,
    'ALTER TABLE `user` ADD COLUMN student_no VARCHAR(32) NULL AFTER id',
    'SELECT 1');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

-- ------------------------------------------------------------
-- 2. password 列（BCrypt 哈希；长度与 trae/my 端一致取 100）
-- ------------------------------------------------------------
SET
@sql := IF(@tbl > 0 AND @has_password = 0,
    'ALTER TABLE `user` ADD COLUMN password VARCHAR(100) NULL AFTER student_no',
    'SELECT 1');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

-- ------------------------------------------------------------
-- 2.1 password_plain 列（明文密码，2026-10-08 新增）
-- ------------------------------------------------------------
-- 用途与风险见 01_schema.sql 里的注释：只为开发排障与答辩演示能在库里直接看到
-- 学生设了什么密码，生产部署前必须删掉整列。
-- 老账号的明文**补不回来**（BCrypt 是单向的），这一列对它们只能是 NULL。
SET
@has_password_plain := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'user' AND COLUMN_NAME = 'password_plain');

SET
@sql := IF(@tbl > 0 AND @has_password_plain = 0,
    'ALTER TABLE `user` ADD COLUMN password_plain VARCHAR(64) NULL COMMENT ''明文密码：仅用于开发排障与答辩演示，生产环境必须删除本列'' AFTER password',
    'SELECT 1');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

-- ------------------------------------------------------------
-- 3. student_no 唯一索引 —— 并发注册的最后一道防线
-- ------------------------------------------------------------
SET
@sql := IF(@tbl > 0 AND @uk_student_no = 0,
    'ALTER TABLE `user` ADD UNIQUE KEY student_no (student_no)',
    'SELECT 1');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

-- ------------------------------------------------------------
-- 4. 验证（手动执行）
-- ------------------------------------------------------------
-- SHOW CREATE TABLE `user`;
--   → 应看到 student_no VARCHAR(32)、password VARCHAR(100)，且 student_no 上有 UNIQUE。
-- SELECT COUNT(*) FROM `user` WHERE student_no IS NULL;
--   → 这些行是微信登录时代留下的历史账号（既没有学号也没有密码），
--     在本端登录不了；数量不为 0 也不影响使用。
