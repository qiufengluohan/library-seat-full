package com.example.libraryseat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.libraryseat.entity.SeatShadow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface SeatShadowMapper extends BaseMapper<SeatShadow> {

    default SeatShadow findBySeatId(Long seatId) {
        return selectOne(Wrappers.<SeatShadow>lambdaQuery()
                .eq(SeatShadow::getSeatId, seatId)
                .last("LIMIT 1"));
    }

    default SeatShadow findByDeviceId(String deviceId) {
        return selectOne(Wrappers.<SeatShadow>lambdaQuery()
                .eq(SeatShadow::getDeviceId, deviceId)
                .last("LIMIT 1"));
    }

    /**
     * 离线任务要处理的行：还标着在线、但已经超过阈值没上报（编码规范 §25）。
     *
     * <p>{@code last_report_at IS NULL} 也要算进来 —— 那是建了影子行但设备从没连上过，
     * 只查 {@code < threshold} 会把它永远漏掉，管理端就会一直显示"在线"。
     *
     * <p>只捞 online=1 的，已经离线的不用重复更新，也就不用重复广播。
     */
    default List<SeatShadow> listStaleOnline(LocalDateTime threshold) {
        return selectList(Wrappers.<SeatShadow>lambdaQuery()
                .eq(SeatShadow::getOnline, 1)
                .and(w -> w.isNull(SeatShadow::getLastReportAt)
                        .or()
                        .lt(SeatShadow::getLastReportAt, threshold)));
    }

    /**
     * 把一行标记为离线，<b>但只在它此刻仍然是"在线且超时"时才改</b>。
     *
     * <p>为什么不直接 {@code updateById}：离线任务是"先 SELECT 一批，再逐行更新"，
     * 这两步之间设备完全可能刚上报了一条数据（online 被置回 1、last_report_at 刷新）。
     * 无条件更新会把一个活着的设备标成离线，管理端于是亮出假的离线徽章，
     * 而下一条上报要 60 秒后才来，这段时间里没人知道为什么它是灰的。
     * 条件写在 WHERE 里让数据库来做这个判断，返回 0 就说明设备刚活过来，跳过即可。
     *
     * <p>{@code updated_at} 不用管，DDL 上是 {@code ON UPDATE CURRENT_TIMESTAMP}。
     *
     * @return 1 = 本次确实把它标成了离线（需要广播）；0 = 设备刚上报过，什么都没改
     */
    @Update("""
            UPDATE seat_shadow SET online = 0
            WHERE id = #{id} AND online = 1
              AND (last_report_at IS NULL OR last_report_at < #{threshold})
            """)
    int markOfflineIfStale(@Param("id") Long id, @Param("threshold") LocalDateTime threshold);

    /** Dashboard 的在线 / 离线设备数。 */
    @Select("SELECT online, COUNT(*) AS cnt FROM seat_shadow GROUP BY online")
    List<Map<String, Object>> countGroupByOnline();
}
