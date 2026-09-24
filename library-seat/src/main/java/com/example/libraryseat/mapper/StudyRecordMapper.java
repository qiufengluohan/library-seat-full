package com.example.libraryseat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.libraryseat.entity.StudyRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

public interface StudyRecordMapper extends BaseMapper<StudyRecord> {

    /**
     * reservation_id 是 UNIQUE，用它判断"这个预约是否已经结算过"。
     * 强制释放和正常离座可能并发触发，先查一次能避免撞唯一键报错。
     */
    default StudyRecord findByReservationId(Long reservationId) {
        return selectOne(Wrappers.<StudyRecord>lambdaQuery()
                .eq(StudyRecord::getReservationId, reservationId)
                .last("LIMIT 1"));
    }

    /** 个人累计学习时长（/api/statistics/me）。COALESCE 保证无记录时返回 0 而不是 null。 */
    @Select("SELECT COALESCE(SUM(duration_minutes), 0) FROM study_record WHERE user_id = #{userId}")
    int sumMinutesByUserId(@Param("userId") Long userId);

    /** 个人累计学习次数。 */
    @Select("SELECT COUNT(*) FROM study_record WHERE user_id = #{userId}")
    int countByUserId(@Param("userId") Long userId);

    /** 全馆累计学习时长（管理端统计报表）。 */
    @Select("SELECT COALESCE(SUM(duration_minutes), 0) FROM study_record")
    int sumAllMinutes();

    /** 全馆累计学习次数。 */
    @Select("SELECT COUNT(*) FROM study_record")
    int countAll();

    /** Dashboard 的"当天学习总时长"。起始时间由 Java 侧按 Asia/Shanghai 算好传入。 */
    @Select("SELECT COALESCE(SUM(duration_minutes), 0) FROM study_record WHERE start_time >= #{start}")
    int sumMinutesSince(@Param("start") LocalDateTime start);
}
