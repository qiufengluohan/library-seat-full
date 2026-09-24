package com.example.libraryseat.service.impl;

import com.example.libraryseat.enums.SeatStatus;
import com.example.libraryseat.mapper.ReservationMapper;
import com.example.libraryseat.mapper.SeatMapper;
import com.example.libraryseat.mapper.SeatShadowMapper;
import com.example.libraryseat.mapper.ViolationMapper;
import com.example.libraryseat.service.StatisticsService;
import com.example.libraryseat.service.StudyRecordService;
import com.example.libraryseat.util.TimeUtil;
import com.example.libraryseat.vo.DashboardVO;
import com.example.libraryseat.vo.StatisticsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final SeatMapper seatMapper;
    private final SeatShadowMapper seatShadowMapper;
    private final ReservationMapper reservationMapper;
    private final ViolationMapper violationMapper;
    private final StudyRecordService studyRecordService;

    @Override
    public DashboardVO dashboard() {
        // "今日"的起点在这里算一次，四个计数共用，
        // 不用 MySQL 的 CURDATE()：应用时区和数据库时区不一致时会差出一天
        LocalDateTime todayStart = TimeUtil.startOfToday();

        DashboardVO vo = new DashboardVO();
        Map<Integer, Integer> seatsByStatus = new HashMap<>();
        int totalSeats = 0;
        for (Map<String, Object> row : seatMapper.countGroupByStatus()) {
            int count = count(row);
            totalSeats += count;
            Integer status = asInt(row.get("status"));
            if (status != null) {
                seatsByStatus.merge(status, count, Integer::sum);
            } else {
                log.warn("seat 表出现 status 为空的行，已计入总数但不归类");
            }
        }

        vo.setTotalSeats(totalSeats);
        vo.setFreeSeats(seatsByStatus.getOrDefault(SeatStatus.FREE.getCode(), 0));
        vo.setReservedSeats(seatsByStatus.getOrDefault(SeatStatus.RESERVED.getCode(), 0));
        vo.setUsingSeats(seatsByStatus.getOrDefault(SeatStatus.USING.getCode(), 0));
        vo.setAwaySeats(seatsByStatus.getOrDefault(SeatStatus.AWAY.getCode(), 0));
        vo.setAlarmSeats(seatsByStatus.getOrDefault(SeatStatus.ALARM.getCode(), 0));

        int online = countOnlineDevices();
        vo.setOnlineDevices(online);
        // 从没上报过的座位没有影子行，一律算离线。
        // 口径和 SeatVO / DeviceVO 里 "shadow == null → online = false" 完全一致，
        // 否则大屏的在线数会和座位网格上的角标对不上。
        vo.setOfflineDevices(Math.max(0, totalSeats - online));

        vo.setTodayReservations((int) reservationMapper.countSince(todayStart));
        vo.setTodayUsers((int) reservationMapper.countDistinctUsersSince(todayStart));
        vo.setTodayStudyMinutes(studyRecordService.todayMinutes());
        vo.setTodayViolations((int) violationMapper.countSince(todayStart));
        return vo;
    }

    @Override
    public StatisticsVO personal(Long userId) {
        return studyRecordService.personalStatistics(userId);
    }

    @Override
    public StatisticsVO overall() {
        return studyRecordService.overallStatistics();
    }

    /** 影子里 online = 1 的行数。GROUP BY 一次查完，不要查两遍。 */
    private int countOnlineDevices() {
        List<Map<String, Object>> rows = seatShadowMapper.countGroupByOnline();
        int online = 0;
        for (Map<String, Object> row : rows) {
            if (Integer.valueOf(1).equals(asInt(row.get("online")))) {
                online += count(row);
            }
        }
        return online;
    }

    /** COUNT(*) 的列别名统一是 cnt。 */
    private static int count(Map<String, Object> row) {
        Integer value = asInt(row.get("cnt"));
        return value == null ? 0 : value;
    }

    /**
     * MyBatis 把聚合结果放进 Map 时不套驼峰转换，类型也由 JDBC 决定：
     * COUNT(*) 是 Long，status 是 Integer。统一按 Number 取，
     * 别写 (Integer) row.get("cnt") —— 那样会在 ClassCastException 上浪费一下午。
     */
    private static Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
