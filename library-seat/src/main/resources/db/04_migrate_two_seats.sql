-- ============================================================
-- 04 增量迁移：单座位 → 双座位（docs/05-双座位改造方案.md §5.1）
-- 执行顺序：01_schema.sql / 02_seed.sql 之后，后端重启之前
--
-- 【什么时候需要跑】
--   库里已经有单座位的 seat / seat_shadow 结构（device_id 单列唯一），
--   要升级成一台设备带两个座位。全新环境直接跑 01_schema.sql 就够了，
--   本文件跑了也无害。
--
-- 【本文件是幂等的】每一步都先查 information_schema 再决定做不做，
-- 反复执行不会报错，也不会重复插入 A4-204。
--
-- 【改动内容】
--   1) seat / seat_shadow 各加 seat_no 列（老行默认 1）
--   2) 唯一键从 device_id 单列 → (device_id, seat_no)
--   3) 现有 SEAT_001 视为座位 1，补一行座位 2（A4-204）
--
-- 【注意】
--   · 本脚本不碰 seat_shadow 的数据，影子行仍由设备首报时自动创建；
--     老影子行会带 seat_no = 1，正好属于座位 1。
--   · 后端代码必须先于历史数据假设 (device_id, seat_no) 已存在，
--     所以**先跑本脚本，再重启后端**。
-- ============================================================

USE
library_seat;

SET
@has_seat_table := (SELECT COUNT(*) FROM information_schema.TABLES
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'seat');
SET
@has_shadow_table := (SELECT COUNT(*) FROM information_schema.TABLES
                          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'seat_shadow');

-- ------------------------------------------------------------
-- 1. seat_no 列
-- ------------------------------------------------------------
SET
@has_seat_no := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'seat' AND COLUMN_NAME = 'seat_no');

SET
@sql := IF(@has_seat_table > 0 AND @has_seat_no = 0,
    'ALTER TABLE seat ADD COLUMN seat_no TINYINT NOT NULL DEFAULT 1 COMMENT ''同一台设备的座位序号：1 / 2'' AFTER device_id',
    'SELECT 1');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET
@has_shadow_seat_no := (SELECT COUNT(*) FROM information_schema.COLUMNS
                            WHERE TABLE_SCHEMA = DATABASE()
                              AND TABLE_NAME = 'seat_shadow' AND COLUMN_NAME = 'seat_no');

SET
@sql := IF(@has_shadow_table > 0 AND @has_shadow_seat_no = 0,
    'ALTER TABLE seat_shadow ADD COLUMN seat_no TINYINT NOT NULL DEFAULT 1 COMMENT ''同一台设备的座位序号：1 / 2'' AFTER device_id',
    'SELECT 1');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

-- ------------------------------------------------------------
-- 2. 删掉旧的 device_id 单列唯一索引
-- ------------------------------------------------------------
-- 不硬编码索引名：MySQL 默认叫 device_id，但手工建库时可能取了别的名字。
-- 这里找"只包含 device_id 一列的唯一索引"，避免误删新的复合索引。
SET
@old_seat_uk := (
    SELECT INDEX_NAME FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'seat'
      AND COLUMN_NAME = 'device_id' AND NON_UNIQUE = 0
      AND INDEX_NAME <> 'uk_seat_device_no'
    GROUP BY INDEX_NAME
    HAVING COUNT(*) = 1
    LIMIT 1);

SET
@sql := IF(@has_seat_table > 0 AND @old_seat_uk IS NOT NULL,
    CONCAT('ALTER TABLE seat DROP INDEX `', @old_seat_uk, '`'),
    'SELECT 1');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET
@old_shadow_uk := (
    SELECT INDEX_NAME FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'seat_shadow'
      AND COLUMN_NAME = 'device_id' AND NON_UNIQUE = 0
      AND INDEX_NAME <> 'uk_shadow_device_no'
    GROUP BY INDEX_NAME
    HAVING COUNT(*) = 1
    LIMIT 1);

SET
@sql := IF(@has_shadow_table > 0 AND @old_shadow_uk IS NOT NULL,
    CONCAT('ALTER TABLE seat_shadow DROP INDEX `', @old_shadow_uk, '`'),
    'SELECT 1');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

-- ------------------------------------------------------------
-- 3. 加复合唯一键 (device_id, seat_no)
-- ------------------------------------------------------------
SET
@seat_uk := (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'seat'
                   AND INDEX_NAME = 'uk_seat_device_no');

SET
@sql := IF(@has_seat_table > 0 AND @seat_uk = 0,
    'ALTER TABLE seat ADD UNIQUE KEY uk_seat_device_no (device_id, seat_no)',
    'SELECT 1');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET
@shadow_uk := (SELECT COUNT(*) FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'seat_shadow'
                     AND INDEX_NAME = 'uk_shadow_device_no');

SET
@sql := IF(@has_shadow_table > 0 AND @shadow_uk = 0,
    'ALTER TABLE seat_shadow ADD UNIQUE KEY uk_shadow_device_no (device_id, seat_no)',
    'SELECT 1');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

-- ------------------------------------------------------------
-- 4. 现有座位统一归到座位 1，再补座位 2
-- ------------------------------------------------------------
-- 加列时 DEFAULT 1 已经把老行填成 1；这条 UPDATE 只是兜底
-- （比如有人手工把某行改成了 0 或 NULL）。
UPDATE seat
SET seat_no = 1
WHERE seat_no IS NULL
   OR seat_no < 1;

INSERT INTO seat (seat_code, area, floor, device_id, seat_no, status)
SELECT 'A4-204', '科技阅览室', 4, 'SEAT_001', 2, 0
FROM DUAL
WHERE EXISTS (SELECT 1 FROM seat WHERE device_id = 'SEAT_001' AND seat_no = 1)
  AND NOT EXISTS (SELECT 1 FROM seat WHERE device_id = 'SEAT_001' AND seat_no = 2);

-- ------------------------------------------------------------
-- 5. 验证（手动执行）
-- ------------------------------------------------------------
-- SHOW INDEX FROM seat;
--   → 应有 uk_seat_device_no (device_id, seat_no)，且不再有 device_id 单列唯一索引。
-- SHOW INDEX FROM seat_shadow;
--   → 应有 uk_shadow_device_no (device_id, seat_no)。
-- SELECT id, seat_code, device_id, seat_no, status FROM seat ORDER BY device_id, seat_no;
--   → SEAT_001 两行：A4-203 / seat_no=1、A4-204 / seat_no=2
-- SELECT id, device_id, seat_no, pressure_adc, online FROM seat_shadow;
--   → 老影子行 seat_no=1；座位 2 的行在第一条 _2 上报到达时自动创建
