package com.example.libraryseat.service;

import com.example.libraryseat.vo.DashboardVO;
import com.example.libraryseat.vo.StatisticsVO;

/**
 * 统计聚合。编码规范 §19。
 *
 * <p>这个 Service 自己不写任何数据，只做<b>跨表汇总</b>。
 * 存在的意义是让 Controller 不用同时注入五六个 Service ——
 * Dashboard 一个接口就要拼座位、设备、订单、学习记录、违规五张表的数据。
 */
public interface StatisticsService {

    /**
     * 管理端首页快照（{@code GET /api/admin/dashboard}，§11.7 十二个字段）。
     *
     * <p>一次请求拿全，<b>不要</b>拆成六个小接口让前端并发去调 ——
     * Dashboard.vue 打开时只发这一个请求，拆开后首屏会变成六次往返，
     * 而且六个数字来自六个不同时刻，加起来可能对不上。
     *
     * <p>座位状态分布用 {@code GROUP BY status} 一次查完，
     * 不要写五个 COUNT(*)。
     *
     * <p>"今日"一律按 Asia/Shanghai 的当天 00:00 起算（{@code TimeUtil.ZONE}），
     * 不要用 MySQL 的 CURDATE() —— 服务器时区和数据库时区不一致时会差出一天。
     */
    DashboardVO dashboard();

    /** 个人学习统计（{@code GET /api/statistics/me}，小程序"我的"页展示） */
    StatisticsVO personal(Long userId);

    /** 全馆学习统计（{@code GET /api/admin/statistics}，违规与统计页展示） */
    StatisticsVO overall();
}
