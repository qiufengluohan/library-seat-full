package com.example.libraryseat.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预约状态解析测试。
 *
 * <p>{@code parseFilter} 直接决定小程序"我的预约"四个页签各自显示什么，
 * 而且它的返回值有<b>三种</b>含义（null = 不过滤 / 非空集合 = IN 条件 /
 * 空集合 = 查不到），调用方 {@code ReservationMapper.listByUserId} 对空集合
 * 必须提前 return —— 否则 {@code IN ()} 会退化成查全部历史订单。
 * 这个语义靠读代码很容易看漏，所以钉在这里。
 */
class ReservationStatusTest {

    @Test
    @DisplayName("parse：库里的字符串还原成枚举，大小写和空格都容错")
    void parseToleratesCaseAndWhitespace() {
        assertEquals(ReservationStatus.USING, ReservationStatus.parse("USING"));
        assertEquals(ReservationStatus.USING, ReservationStatus.parse(" using "));
    }

    @Test
    @DisplayName("parse：脏数据返回 null，不抛 IllegalArgumentException")
    void parseReturnsNullForGarbage() {
        assertNull(ReservationStatus.parse(null));
        assertNull(ReservationStatus.parse(""));
        assertNull(ReservationStatus.parse("DONE"));
    }

    @Test
    @DisplayName("进行中的状态只有 RESERVED / USING / AWAY 三个")
    void activeSetHasExactlyThreeMembers() {
        assertEquals(Set.of(ReservationStatus.RESERVED, ReservationStatus.USING, ReservationStatus.AWAY),
                ReservationStatus.activeSet());

        assertTrue(ReservationStatus.RESERVED.isActive());
        assertFalse(ReservationStatus.COMPLETED.isActive());
        assertFalse(ReservationStatus.CANCELLED.isActive());
        assertFalse(ReservationStatus.TIMEOUT.isActive());
    }

    @Test
    @DisplayName("parseFilter：null / 空 / ALL 都表示不过滤")
    void filterAllMeansNoCondition() {
        assertNull(ReservationStatus.parseFilter(null));
        assertNull(ReservationStatus.parseFilter(""));
        assertNull(ReservationStatus.parseFilter("  "));
        assertNull(ReservationStatus.parseFilter("ALL"));
        assertNull(ReservationStatus.parseFilter("all"));
    }

    @Test
    @DisplayName("parseFilter：FINISHED 归并三个终态")
    void filterFinishedExpandsToThreeTerminalStates() {
        assertEquals(Set.of(ReservationStatus.COMPLETED, ReservationStatus.CANCELLED, ReservationStatus.TIMEOUT),
                ReservationStatus.parseFilter("FINISHED"));
    }

    @Test
    @DisplayName("parseFilter：单个状态返回单元素集合")
    void filterSingleStatus() {
        assertEquals(Set.of(ReservationStatus.RESERVED), ReservationStatus.parseFilter("RESERVED"));
    }

    @Test
    @DisplayName("parseFilter：认不出来的值返回空集合，让调用方查不到而不是查全部")
    void filterUnknownYieldsEmptySet() {
        Set<ReservationStatus> matched = ReservationStatus.parseFilter("WHATEVER");
        assertTrue(matched.isEmpty());
    }

    @Test
    @DisplayName("进行中和已结束两个集合不重叠，且合起来正好是全部状态")
    void activeAndFinishedPartitionAllStates() {
        for (ReservationStatus status : ReservationStatus.values()) {
            assertEquals(status.isActive(), ReservationStatus.activeSet().contains(status));
            assertEquals(!status.isActive(), ReservationStatus.finishedSet().contains(status));
        }
    }
}
