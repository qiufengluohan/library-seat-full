package com.example.libraryseat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.libraryseat.entity.Reservation;
import com.example.libraryseat.enums.ReservationStatus;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public interface ReservationMapper extends BaseMapper<Reservation> {

    /**
     * 当前进行中的预约（RESERVED / USING / AWAY），编码规范 §21 用它拦"已有进行中的预约"。
     *
     * <p>正常情况下最多一条。加 LIMIT 1 是为了在脏数据（历史并发写出的两条 active）
     * 下不抛 TooManyResultsException —— selectOne 遇到多行会直接报错。
     */
    default Reservation findCurrentByUserId(Long userId) {
        return selectOne(Wrappers.<Reservation>lambdaQuery()
                .eq(Reservation::getUserId, userId)
                .in(Reservation::getStatus, names(ReservationStatus.activeSet()))
                .orderByDesc(Reservation::getId)
                .last("LIMIT 1"));
    }

    /** RFID 签到只认 RESERVED 的单子（§22 / 方案 §26）。 */
    default Reservation findReservedByUserId(Long userId) {
        return selectOne(Wrappers.<Reservation>lambdaQuery()
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getStatus, ReservationStatus.RESERVED.name())
                .orderByDesc(Reservation::getId)
                .last("LIMIT 1"));
    }

    /** 某个座位当前进行中的预约。座位详情、强制释放都要用。 */
    default Reservation findCurrentBySeatId(Long seatId) {
        return selectOne(Wrappers.<Reservation>lambdaQuery()
                .eq(Reservation::getSeatId, seatId)
                .in(Reservation::getStatus, names(ReservationStatus.activeSet()))
                .orderByDesc(Reservation::getId)
                .last("LIMIT 1"));
    }

    /**
     * 我的预约列表，按 reserve_time 倒序（小程序 docs/04 §4.6）。
     *
     * @param statuses null 表示不过滤；空集合表示"什么都别返回"
     *                 （前端传了非法 status 值时走这里，不要退化成全量）
     */
    default List<Reservation> listByUserId(Long userId, Set<ReservationStatus> statuses) {
        // parseFilter 对非法 status 值返回空集合。这种时候必须返回空列表：
        // MyBatis-Plus 遇到空集合会把整个 IN 条件丢掉，退化成"查全部历史记录"。
        if (statuses != null && statuses.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<Reservation>lambdaQuery()
                .eq(Reservation::getUserId, userId)
                .in(statuses != null, Reservation::getStatus,
                        statuses == null ? List.<String>of() : names(statuses))
                .orderByDesc(Reservation::getReserveTime)
                .orderByDesc(Reservation::getId));
    }

    /** 超时任务 A：RESERVED 且 reserve_expire_at 已过（§24）。 */
    default List<Reservation> listExpiredReserved(LocalDateTime now) {
        return selectList(Wrappers.<Reservation>lambdaQuery()
                .eq(Reservation::getStatus, ReservationStatus.RESERVED.name())
                .isNotNull(Reservation::getReserveExpireAt)
                .lt(Reservation::getReserveExpireAt, now));
    }

    /** 超时任务 B：AWAY 且 leave_expire_at 已过（§24）。 */
    default List<Reservation> listExpiredAway(LocalDateTime now) {
        return selectList(Wrappers.<Reservation>lambdaQuery()
                .eq(Reservation::getStatus, ReservationStatus.AWAY.name())
                .isNotNull(Reservation::getLeaveExpireAt)
                .lt(Reservation::getLeaveExpireAt, now));
    }

    /**
     * 当天预约次数。起始时间由 Java 侧按 Asia/Shanghai 算好传进来，
     * 不用 CURDATE() —— MySQL 服务器时区和应用不一致时会差出一天。
     */
    @Select("SELECT COUNT(*) FROM reservation WHERE reserve_time >= #{start}")
    long countSince(@Param("start") LocalDateTime start);

    /** 当天学习人数：按 user_id 去重，同一个人约三次只算一个。 */
    @Select("SELECT COUNT(DISTINCT user_id) FROM reservation WHERE reserve_time >= #{start}")
    long countDistinctUsersSince(@Param("start") LocalDateTime start);

    /**
     * 原子改订单状态：只有当前 status 还等于 {@code from} 时才改成 {@code to}。
     *
     * <p>存在的理由是<b>定时任务和用户操作会同时命中同一条订单</b>：
     * 学生在 14:59:59 点了 release，超时任务在 15:00:00 扫到这条 AWAY 单。
     * 两边都"先查再改"的话，后提交的那个会把前一个的结果覆盖掉
     * （典型症状：订单变成 COMPLETED 但 study_record 写了两条，
     * 或者座位被 FREE 了两次、广播了两遍）。
     *
     * <p>用法：先 CAS，返回 1 才继续写时间戳和后续动作，返回 0 直接跳过 ——
     * 说明这条订单已经被别人流转走了。
     *
     * <p>{@code updated_at} 由 DDL 的 ON UPDATE CURRENT_TIMESTAMP 自动维护。
     */
    @Update("UPDATE reservation SET status = #{to} WHERE id = #{id} AND status = #{from}")
    int compareAndSetStatus(@Param("id") Long id,
                            @Param("from") String from,
                            @Param("to") String to);

    static Collection<String> names(Collection<ReservationStatus> statuses) {
        return statuses.stream().map(Enum::name).collect(Collectors.toList());
    }
}
