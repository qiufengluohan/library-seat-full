package com.example.libraryseat.enums;

import lombok.Getter;

import java.util.EnumSet;
import java.util.Set;

/**
 * 预约状态，编码规范 §5.2。以字符串形式入库和下发，前端按字符串比对。
 *
 * <p>进行中的状态只有 RESERVED / USING / AWAY 三个；其余都是终态。
 * "当前是否有进行中的预约"这个判断全系统只认 {@link #isActive()}，
 * 不要在别处另写一份状态列表。
 */
@Getter
public enum ReservationStatus {

    RESERVED("待签到"),
    USING("使用中"),
    AWAY("暂离"),
    COMPLETED("已完成"),
    CANCELLED("已取消"),
    TIMEOUT("已超时");

    private static final Set<ReservationStatus> ACTIVE =
            EnumSet.of(RESERVED, USING, AWAY);

    /**
     * 小程序"已完成"筛选传的 FINISHED 不是本枚举成员，
     * 它要在服务端归并成这三个终态（小程序 docs/04 §4.6）。
     */
    private static final Set<ReservationStatus> FINISHED =
            EnumSet.of(COMPLETED, CANCELLED, TIMEOUT);

    private final String label;

    ReservationStatus(String label) {
        this.label = label;
    }

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public static Set<ReservationStatus> activeSet() {
        return ACTIVE;
    }

    public static Set<ReservationStatus> finishedSet() {
        return FINISHED;
    }

    /**
     * 把库里的字符串还原成单个枚举。
     *
     * <p>状态流转前都要先问"这条订单现在到底是什么状态"，
     * 用这个方法而不是 {@code valueOf} —— 后者遇到脏数据会抛
     * IllegalArgumentException，被全局异常处理兜成 500，
     * 而一条历史遗留的坏记录不该让整个接口挂掉。
     *
     * @return 认不出来时返回 null，调用方按"状态不符，拒绝操作"处理
     */
    public static ReservationStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 解析查询参数。除枚举名之外还接受 FINISHED 这个前端专用的归并值，
     * 因此返回的是集合而不是单个枚举。
     *
     * @param raw 状态字符串，null / 空 / ALL 表示不过滤
     * @return null 表示不过滤；否则为需要匹配的状态集合
     */
    public static Set<ReservationStatus> parseFilter(String raw) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw)) {
            return null;
        }
        if ("FINISHED".equalsIgnoreCase(raw)) {
            return FINISHED;
        }
        try {
            return EnumSet.of(valueOf(raw.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return EnumSet.noneOf(ReservationStatus.class);
        }
    }
}
