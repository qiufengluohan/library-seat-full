package com.example.libraryseat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.libraryseat.entity.Seat;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

public interface SeatMapper extends BaseMapper<Seat> {

    /**
     * 设备上报只带 device_id，靠这个方法找到对应座位。
     * device_id 在库层是 UNIQUE，所以最多一行。
     */
    default Seat selectByDeviceId(String deviceId) {
        return selectOne(Wrappers.<Seat>lambdaQuery()
                .eq(Seat::getDeviceId, deviceId)
                .last("LIMIT 1"));
    }

    /**
     * 原子改状态：只有当前 status 等于 {@code from} 时才改成 {@code to}。
     *
     * <p>抢座并发靠它兜底。判断条件写在 WHERE 里由数据库行锁保证互斥，
     * 返回 1 代表"我抢到了"，返回 0 代表"别人已经改过了"。
     * 千万不要改成"先 selectById 判断再 updateById"—— 那样两个请求会同时通过判断。
     *
     * <p>{@code updated_at} 由 DDL 的 ON UPDATE CURRENT_TIMESTAMP 自动维护，
     * 不用写进 SET。
     */
    @Update("UPDATE seat SET status = #{to} WHERE id = #{seatId} AND status = #{from}")
    int compareAndSetStatus(@Param("seatId") Long seatId,
                            @Param("from") Integer from,
                            @Param("to") Integer to);

    /**
     * Dashboard 的座位状态分布，一次查完而不是查五次。
     *
     * <p>返回的 key 就是列别名（{@code status} / {@code cnt}）。
     * MyBatis 对 Map 结果不会套用驼峰转换，所以别名里不要带下划线。
     */
    @Select("SELECT status, COUNT(*) AS cnt FROM seat GROUP BY status")
    List<Map<String, Object>> countGroupByStatus();
}
