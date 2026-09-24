package com.example.libraryseat.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 学习统计，编码规范 §11.8。
 *
 * <p>全馆统计（/api/admin/statistics）和个人统计（/api/statistics/me）共用这一个结构。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatisticsVO {

    private Integer totalStudyMinutes;

    private Integer totalStudyCount;

    /**
     * 平均每次时长。次数为 0 时必须给 0，不能给 null 也不能除零 ——
     * 前端会直接把它格式化成"x小时y分钟"显示。
     */
    private Integer averageStudyMinutes;

    public static StatisticsVO of(int totalMinutes, int totalCount) {
        int average = totalCount == 0 ? 0 : totalMinutes / totalCount;
        return new StatisticsVO(totalMinutes, totalCount, average);
    }
}
