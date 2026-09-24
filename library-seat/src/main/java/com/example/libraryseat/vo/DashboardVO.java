package com.example.libraryseat.vo;

import lombok.Data;

/**
 * 管理端实时大屏聚合快照，编码规范 §11.7 / 方案 §31。
 *
 * <p>一次请求返回全部数字，前端不需要为了大屏打八个接口。
 * 三组数据各自独立：座位状态分布、设备在线情况、当天业务量。
 *
 * <p>"当天"的起点由后端按 Asia/Shanghai 的零点算，不用 MySQL 的 CURDATE()，
 * 避免数据库服务器时区不同导致统计跨天错位。
 */
@Data
public class DashboardVO {

    private Integer totalSeats;
    private Integer freeSeats;
    private Integer reservedSeats;
    private Integer usingSeats;
    private Integer awaySeats;
    private Integer alarmSeats;

    private Integer onlineDevices;
    private Integer offlineDevices;

    private Integer todayReservations;
    private Integer todayUsers;
    private Integer todayStudyMinutes;
    private Integer todayViolations;
}
