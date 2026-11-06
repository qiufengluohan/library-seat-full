-- ============================================================
-- 智慧图书馆座位智能管理系统 - 数据库初始化脚本
-- 依据《实际编码规范（最终统一开发版）》§8，共 9 张表
-- 命名规范：数据库全部 snake_case（§4）
-- ============================================================

CREATE
DATABASE IF NOT EXISTS library_seat
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE
library_seat;


-- ------------------------------------------------------------
-- 8.1 user 学生用户（学生自助注册产生：学号 + 密码）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    -- 2026-10-08：微信登录时代的 openid 列已从库里删除
    --（另一份副本 trae/my 也同步去掉了对它的引用），新库不再建这一列。
    student_no
    VARCHAR
(
    32
) NOT NULL UNIQUE,
    password VARCHAR
(
    100
) NOT NULL,
    -- ⚠️ 明文密码。这是【有意的偏离】：编码规范 §35 要求禁止明文，
    --    这里额外存一列，只为开发排障与答辩演示能直接看到学生设了什么密码。
    --    · 只在注册时写入；本端没有自助改密，所以不会出现"改密后忘了同步"；
    --    · 任何 VO / 接口都不得下发它，也不许写进日志；
    --    · 生产部署前必须删掉整列：
    --          ALTER TABLE `user` DROP COLUMN password_plain;
    password_plain VARCHAR
(
    64
) NULL,
    nickname VARCHAR
(
    64
),
    avatar_url VARCHAR
(
    255
),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    );

-- 密码必须使用 BCrypt（编码规范 §35，与 admin_user 同一口径），禁止明文。
-- 长度取 100 而不是 60：BCrypt 输出固定 60 字符，余下 40 是给以后可能出现的
-- 前缀/算法标识变化留的空间；这个长度与 trae/my 端保持一字不差 ——
-- 两套共用一个库时，列定义必须一致，否则谁先建表谁说了算。
--
-- student_no 上的 UNIQUE 索引是并发注册的最后一道防线：两个请求同时注册同一个
-- 学号时，后到的那条 INSERT 会被数据库拦下（后端捕获 DuplicateKeyException 后
-- 提示"该学号已注册"），不依赖"先查再插"那种有竞态的写法。
--
-- ⚠️ 本文件是 CREATE TABLE IF NOT EXISTS：库里若已经有 user 表，本文件一行都不改。
--    老库升级请执行 03_migrate_student_account.sql（幂等，可反复跑）。


-- ------------------------------------------------------------
-- 8.2 admin_user 管理员（单管理员，不做角色/权限树）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS admin_user
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    username
    VARCHAR
(
    50
) NOT NULL UNIQUE,
    password VARCHAR
(
    255
) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    );

-- 密码必须使用 BCrypt（编码规范 §35），禁止明文。
-- 管理员账号在 02_seed.sql 里种子（含默认密码说明和改密步骤），本文件只建表。


-- ------------------------------------------------------------
-- 8.3 seat 座位基础信息
-- status: 0 FREE / 1 RESERVED / 2 USING / 3 AWAY / 4 ALARM（§5.1）
--
-- 双座位改造（docs/05）：一台 STM32 带两个座位，device_id 不再唯一，
-- 定位座位用 (device_id, seat_no)，seat_no 取 1 / 2。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seat
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    seat_code
    VARCHAR
(
    50
) NOT NULL UNIQUE,
    area VARCHAR
(
    50
),
    floor INT,
    device_id VARCHAR
(
    64
) NOT NULL,
    seat_no TINYINT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_seat_device_no
(
    device_id,
    seat_no
)
    );


-- ------------------------------------------------------------
-- 8.4 reservation 预约/使用记录
-- status: RESERVED / USING / AWAY / COMPLETED / CANCELLED / TIMEOUT（§5.2）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS reservation
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    user_id
    BIGINT
    NOT
    NULL,
    seat_id
    BIGINT
    NOT
    NULL,
    status
    VARCHAR
(
    20
) NOT NULL,
    reserve_time DATETIME NOT NULL,
    sign_time DATETIME NULL,
    leave_time DATETIME NULL,
    return_time DATETIME NULL,
    release_time DATETIME NULL,
    reserve_expire_at DATETIME NULL,
    leave_expire_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_reservation_user_status
(
    user_id,
    status
),
    INDEX idx_reservation_seat_status
(
    seat_id,
    status
),
    INDEX idx_reservation_reserve_expire
(
    reserve_expire_at
),
    INDEX idx_reservation_leave_expire
(
    leave_expire_at
)
    );


-- ------------------------------------------------------------
-- 8.5 rfid_user RFID 与学生绑定
-- 共享读卡器只上报 rfid_uid，由此表解析出 user_id（方案 §26）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rfid_user
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    rfid_uid
    VARCHAR
(
    64
) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL UNIQUE,
    bind_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    );


-- ------------------------------------------------------------
-- 8.6 seat_shadow 设备最新状态（设备影子）
-- 只保存"现在设备是什么状态"，不保存历史、不保存 rfid_uid（方案 §20）
--
-- 双座位改造（docs/05）：一个座位一行，(device_id, seat_no) 唯一。
-- seat_id 仍是 UNIQUE，与 seat 表一一对应。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS seat_shadow
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    seat_id
    BIGINT
    NOT
    NULL
    UNIQUE,
    device_id
    VARCHAR
(
    64
) NOT NULL,
    seat_no TINYINT NOT NULL DEFAULT 1,
    pressure_adc INT NOT NULL DEFAULT 0,
    pir_state TINYINT NOT NULL DEFAULT 0,
    alarm_flag TINYINT NOT NULL DEFAULT 0,
    online TINYINT NOT NULL DEFAULT 0,
    last_report_at DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shadow_device_no
(
    device_id,
    seat_no
)
    );


-- ------------------------------------------------------------
-- 8.7 study_record 学习时长
-- 暂离阶段不拆分，按整个 USING 周期统计（编码规范 §43）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS study_record
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    user_id
    BIGINT
    NOT
    NULL,
    seat_id
    BIGINT
    NOT
    NULL,
    reservation_id
    BIGINT
    NOT
    NULL
    UNIQUE,
    start_time
    DATETIME
    NOT
    NULL,
    end_time
    DATETIME
    NULL,
    duration_minutes
    INT
    NOT
    NULL
    DEFAULT
    0,
    created_at
    DATETIME
    NOT
    NULL
    DEFAULT
    CURRENT_TIMESTAMP
);


-- ------------------------------------------------------------
-- 8.8 violation 违规/异常
-- type 只有三类：FAKE_OCCUPY / RESERVATION_TIMEOUT / AWAY_TIMEOUT（§44）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS violation
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    user_id
    BIGINT
    NULL,
    seat_id
    BIGINT
    NOT
    NULL,
    type
    VARCHAR
(
    30
) NOT NULL,
    description VARCHAR
(
    255
),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_violation_seat
(
    seat_id
),
    INDEX idx_violation_user
(
    user_id
),
    INDEX idx_violation_created
(
    created_at
)
    );


-- ------------------------------------------------------------
-- 8.9 operation_log 管理员操作日志（方案 §36）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS operation_log
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    admin_id
    BIGINT
    NOT
    NULL,
    operation
    VARCHAR
(
    50
) NOT NULL,
    target VARCHAR
(
    100
),
    description VARCHAR
(
    255
),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_operation_log_created
(
    created_at
)
    );
