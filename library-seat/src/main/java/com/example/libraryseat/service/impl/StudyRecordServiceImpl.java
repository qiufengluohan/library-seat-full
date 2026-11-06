package com.example.libraryseat.service.impl;

import com.example.libraryseat.entity.Reservation;
import com.example.libraryseat.entity.StudyRecord;
import com.example.libraryseat.mapper.StudyRecordMapper;
import com.example.libraryseat.service.StudyRecordService;
import com.example.libraryseat.util.TimeUtil;
import com.example.libraryseat.vo.StatisticsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudyRecordServiceImpl implements StudyRecordService {

    private final StudyRecordMapper studyRecordMapper;

    @Override
    public int createFromReservation(Reservation reservation) {
        if (reservation == null || reservation.getId() == null) {
            return 0;
        }
        // 只预约没签到（RESERVED 超时 / 取消）不产生学习时长：
        // study_record.start_time 是 NOT NULL，而它取的就是 sign_time
        if (reservation.getSignTime() == null) {
            return 0;
        }

        // 幂等第一道闸：强制释放和暂离超时任务可能撞到同一条订单
        if (studyRecordMapper.findByReservationId(reservation.getId()) != null) {
            return 0;
        }

        LocalDateTime end = reservation.getReleaseTime() != null
                ? reservation.getReleaseTime()
                : TimeUtil.now();
        // §23：中间发生过暂离也不拆分，按整个 USING 周期算
        int minutes = TimeUtil.minutesBetween(reservation.getSignTime(), end);

        StudyRecord record = new StudyRecord();
        record.setUserId(reservation.getUserId());
        record.setSeatId(reservation.getSeatId());
        record.setReservationId(reservation.getId());
        record.setStartTime(reservation.getSignTime());
        record.setEndTime(end);
        record.setDurationMinutes(minutes);
        record.setCreatedAt(TimeUtil.now());

        try {
            studyRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 幂等第二道闸，也是真正靠得住的那道：
            // study_record.reservation_id 上有 UNIQUE 索引。
            // 上面的"先查再插"在并发下仍有窗口，撞索引说明别人已经写过了，
            // 静默返回 0 即可 —— 绝不能让重复写入把总时长刷高。
            log.debug("学习记录已存在，跳过: reservationId={}", reservation.getId());
            return 0;
        }
        return minutes;
    }

    @Override
    public StatisticsVO personalStatistics(Long userId) {
        if (userId == null) {
            return StatisticsVO.of(0, 0);
        }
        return StatisticsVO.of(
                studyRecordMapper.sumMinutesByUserId(userId),
                studyRecordMapper.countByUserId(userId));
    }

    @Override
    public StatisticsVO overallStatistics() {
        return StatisticsVO.of(studyRecordMapper.sumAllMinutes(), studyRecordMapper.countAll());
    }

    @Override
    public int todayMinutes() {
        return studyRecordMapper.sumMinutesSince(TimeUtil.startOfToday());
    }
}
