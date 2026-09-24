package com.example.libraryseat.service;

import com.example.libraryseat.entity.Reservation;
import com.example.libraryseat.vo.StatisticsVO;

/**
 * 学习记录与时长统计。编码规范 §19 / §23 / §43。
 *
 * <p>study_record 是<b>论文里"学习时长统计"这一节的唯一数据来源</b>，
 * Dashboard 的"今日学习分钟数"、管理端统计页、小程序"我的"页都读它。
 * 所以每条订单结束时都要落一条，漏了统计就会偏小。
 */
public interface StudyRecordService {

    /**
     * 按订单生成一条学习记录（§23）。
     *
     * <p>{@code start_time = sign_time}，{@code end_time = release_time}，
     * {@code duration_minutes} = 两者差值（分钟，向下取整）。
     *
     * <p><b>暂离不拆分</b>：中间发生过 leave / return 也只写一条，
     * 按整个 USING 周期算。§23 原话是"毕业设计阶段可以直接按照整个
     * USING 周期统计……不要现在就复杂化"。真要精确，
     * 以后再拆多条，不要现在动。
     *
     * <p>幂等：同一 reservation_id 已有记录就不再写。
     * 强制释放和暂离超时可能撞到同一条订单，重复写会让总时长虚高。
     *
     * @return 实际写入的分钟数；没有 sign_time（只预约没签到）时返回 0 且不写记录
     */
    int createFromReservation(Reservation reservation);

    /** 个人学习统计（{@code GET /api/statistics/me}，§13） */
    StatisticsVO personalStatistics(Long userId);

    /** 全馆学习统计（{@code GET /api/admin/statistics}） */
    StatisticsVO overallStatistics();

    /** Dashboard 的"今日学习分钟数"，按 Asia/Shanghai 的今天 00:00 起算 */
    int todayMinutes();
}
