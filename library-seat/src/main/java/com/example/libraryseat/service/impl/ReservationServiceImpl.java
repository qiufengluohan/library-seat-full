package com.example.libraryseat.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.libraryseat.common.BusinessException;
import com.example.libraryseat.config.SeatProperties;
import com.example.libraryseat.dto.ReservationCreateDTO;
import com.example.libraryseat.entity.Reservation;
import com.example.libraryseat.entity.Seat;
import com.example.libraryseat.enums.ReservationStatus;
import com.example.libraryseat.enums.SeatStatus;
import com.example.libraryseat.enums.ViolationType;
import com.example.libraryseat.mapper.ReservationMapper;
import com.example.libraryseat.mapper.SeatMapper;
import com.example.libraryseat.service.DeviceService;
import com.example.libraryseat.service.OperationLogService;
import com.example.libraryseat.service.ReservationService;
import com.example.libraryseat.service.SeatService;
import com.example.libraryseat.service.StudyRecordService;
import com.example.libraryseat.service.ViolationService;
import com.example.libraryseat.util.TimeUtil;
import com.example.libraryseat.vo.ReservationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {

    private final ReservationMapper reservationMapper;
    private final SeatMapper seatMapper;
    private final SeatService seatService;
    private final StudyRecordService studyRecordService;
    private final ViolationService violationService;
    private final OperationLogService operationLogService;
    private final DeviceService deviceService;
    private final SeatProperties seatProperties;

    /* ------------------------------------------------------------
     * 学生端
     * ------------------------------------------------------------ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationVO create(Long userId, ReservationCreateDTO dto) {
        if (dto == null || dto.getSeatId() == null) {
            throw BusinessException.badRequest("缺少座位ID");
        }
        Seat seat = seatService.requireSeat(dto.getSeatId());

        if (reservationMapper.findCurrentByUserId(userId) != null) {
            throw BusinessException.conflict("您已有进行中的预约，请先离座或取消");
        }

        LocalDateTime now = TimeUtil.now();
        Reservation reservation = new Reservation();
        reservation.setUserId(userId);
        reservation.setSeatId(seat.getId());
        reservation.setStatus(ReservationStatus.RESERVED.name());
        reservation.setReserveTime(now);
        reservation.setReserveExpireAt(now.plusMinutes(seatProperties.getReserveTimeoutMinutes()));
        // created_at / updated_at 由 DDL 的 DEFAULT 和 ON UPDATE 维护，不要手写

        // 先插单再抢座，顺序是有意的：
        // updateStatusIf 成功时会立刻广播，把它放在最后一步，
        // 广播出去的就一定是真正提交了的值。抢座失败抛 409，
        // 事务回滚会把上面那条订单一起删掉，不会留下孤儿记录。
        reservationMapper.insert(reservation);
        if (!seatService.updateStatusIf(seat.getId(), SeatStatus.FREE, SeatStatus.RESERVED)) {
            throw BusinessException.conflict("座位已被预约");
        }
        return ReservationVO.of(reservation, seat);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationVO cancel(Long userId, Long reservationId) {
        Reservation reservation = requireOwn(userId, reservationId);
        requireStatus(reservation, ReservationStatus.RESERVED, "只有待签到的预约可以取消");
        transition(reservation, ReservationStatus.RESERVED, ReservationStatus.CANCELLED);
        releaseSeat(reservation.getSeatId(), SeatStatus.RESERVED);
        return toVO(reservation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationVO leave(Long userId, Long reservationId) {
        Reservation reservation = requireOwn(userId, reservationId);
        requireStatus(reservation, ReservationStatus.USING, "只有使用中的预约可以暂离");
        transition(reservation, ReservationStatus.USING, ReservationStatus.AWAY);

        LocalDateTime now = TimeUtil.now();
        reservation.setLeaveTime(now);
        reservation.setLeaveExpireAt(now.plusMinutes(seatProperties.getAwayTimeoutMinutes()));
        reservationMapper.update(null, Wrappers.<Reservation>lambdaUpdate()
                .set(Reservation::getLeaveTime, reservation.getLeaveTime())
                .set(Reservation::getLeaveExpireAt, reservation.getLeaveExpireAt())
                .eq(Reservation::getId, reservation.getId()));

        seatService.updateStatusIf(reservation.getSeatId(), SeatStatus.USING, SeatStatus.AWAY);
        return toVO(reservation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationVO returnSeat(Long userId, Long reservationId) {
        Reservation reservation = requireOwn(userId, reservationId);
        requireStatus(reservation, ReservationStatus.AWAY, "只有暂离中的预约可以回座");
        transition(reservation, ReservationStatus.AWAY, ReservationStatus.USING);

        LocalDateTime now = TimeUtil.now();
        reservation.setReturnTime(now);
        // 回座后暂离倒计时必须停掉，否则定时任务会把一个正在使用的订单扫成超时
        reservation.setLeaveExpireAt(null);
        reservationMapper.update(null, Wrappers.<Reservation>lambdaUpdate()
                .set(Reservation::getReturnTime, now)
                .set(Reservation::getLeaveExpireAt, null)
                .eq(Reservation::getId, reservation.getId()));

        seatService.updateStatusIf(reservation.getSeatId(), SeatStatus.AWAY, SeatStatus.USING);
        return toVO(reservation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationVO release(Long userId, Long reservationId) {
        Reservation reservation = requireOwn(userId, reservationId);
        requireStatus(reservation, ReservationStatus.USING, "只有使用中的预约可以离座");
        transition(reservation, ReservationStatus.USING, ReservationStatus.COMPLETED);

        LocalDateTime now = TimeUtil.now();
        reservation.setReleaseTime(now);
        reservationMapper.update(null, Wrappers.<Reservation>lambdaUpdate()
                .set(Reservation::getReleaseTime, now)
                .eq(Reservation::getId, reservation.getId()));

        int minutes = studyRecordService.createFromReservation(reservation);
        releaseSeat(reservation.getSeatId(), SeatStatus.USING);
        log.info("学生离座: reservationId={}, seatId={}, 学习 {} 分钟",
                reservation.getId(), reservation.getSeatId(), minutes);
        return toVO(reservation);
    }

    @Override
    public ReservationVO getCurrent(Long userId) {
        Reservation current = reservationMapper.findCurrentByUserId(userId);
        return current == null ? null : toVO(current);
    }

    @Override
    public List<ReservationVO> listByUser(Long userId, String statusFilter) {
        Set<ReservationStatus> statuses = ReservationStatus.parseFilter(statusFilter);
        List<Reservation> rows = reservationMapper.listByUserId(userId, statuses);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Seat> seats = loadSeats(rows);
        List<ReservationVO> result = new ArrayList<>(rows.size());
        for (Reservation row : rows) {
            result.add(ReservationVO.of(row, seats.get(row.getSeatId())));
        }
        return result;
    }

    @Override
    public ReservationVO getDetail(Long userId, Long reservationId) {
        return toVO(requireOwn(userId, reservationId));
    }

    /* ------------------------------------------------------------
     * RFID 签到
     * ------------------------------------------------------------ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationVO signIn(Long userId) {
        Reservation reservation = reservationMapper.findReservedByUserId(userId);
        if (reservation == null) {
            // 刷卡人可能只是路过，或者已经签过到了。§22 的伪代码是直接 return，
            // 这不是错误，读卡器那边也没有地方显示错误。
            log.info("刷卡用户没有待签到的预约: userId={}", userId);
            return null;
        }
        transition(reservation, ReservationStatus.RESERVED, ReservationStatus.USING);

        LocalDateTime now = TimeUtil.now();
        reservation.setSignTime(now);
        reservationMapper.update(null, Wrappers.<Reservation>lambdaUpdate()
                .set(Reservation::getSignTime, now)
                .eq(Reservation::getId, reservation.getId()));

        seatService.updateStatusIf(reservation.getSeatId(), SeatStatus.RESERVED, SeatStatus.USING);
        log.info("RFID 签到成功: userId={}, reservationId={}, seatId={}",
                userId, reservation.getId(), reservation.getSeatId());
        return toVO(reservation);
    }

    /* ------------------------------------------------------------
     * 管理员强制释放
     * ------------------------------------------------------------ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReservationVO forceRelease(Long seatId, Long adminId) {
        Seat seat = seatService.requireSeat(seatId);
        Reservation current = reservationMapper.findCurrentBySeatId(seatId);
        if (current == null) {
            // 管理员点了就得有记录，哪怕这次点击什么也没改
            operationLogService.record(adminId, OperationLogService.OP_FORCE_RELEASE,
                    seat.getSeatCode(), "座位当前没有进行中的预约");
            return null;
        }

        SeatStatus seatWas = SeatStatus.fromReservation(ReservationStatus.parse(current.getStatus()));
        transition(current, ReservationStatus.parse(current.getStatus()), ReservationStatus.COMPLETED);

        LocalDateTime now = TimeUtil.now();
        current.setReleaseTime(now);
        reservationMapper.update(null, Wrappers.<Reservation>lambdaUpdate()
                .set(Reservation::getReleaseTime, now)
                .eq(Reservation::getId, current.getId()));

        // 只预约没签到时 createFromReservation 内部会跳过，返回 0
        int minutes = studyRecordService.createFromReservation(current);

        // 强制释放是管理员意志，座位状态无条件归位（连 ALARM 一起盖掉）：
        // 这正是"东西占着座、告警一直在响"时管理员要按的那个按钮。
        // 影子里的 alarm_flag 不动，设备还在报就继续报，管理端仍会显示告警角标，
        // 需要的话再点一次"消除告警"。
        seatService.updateStatus(seatId, SeatStatus.FREE);
        deviceService.sendDisplayClear(seat.getDeviceId());

        operationLogService.record(adminId, OperationLogService.OP_FORCE_RELEASE,
                seat.getSeatCode(),
                String.format("强制释放座位，本次学习 %d 分钟（原状态 %s）", minutes, seatWas.getLabel()));
        log.info("管理员强制释放: adminId={}, seatId={}, reservationId={}", adminId, seatId, current.getId());
        return ReservationVO.of(current, seat);
    }

    /* ------------------------------------------------------------
     * 定时任务（§24）
     * ------------------------------------------------------------ */

    @Override
    public int releaseExpiredReservations() {
        List<Reservation> expired = reservationMapper.listExpiredReserved(TimeUtil.now());
        int handled = 0;
        for (Reservation reservation : expired) {
            try {
                if (timeoutReserved(reservation)) {
                    handled++;
                }
            } catch (Exception e) {
                // 一条脏数据不该让整轮扫描停摆，下一轮还会再扫到它
                log.error("处理预约超时失败: reservationId={}", reservation.getId(), e);
            }
        }
        return handled;
    }

    @Override
    public int releaseExpiredAway() {
        List<Reservation> expired = reservationMapper.listExpiredAway(TimeUtil.now());
        int handled = 0;
        for (Reservation reservation : expired) {
            try {
                if (timeoutAway(reservation)) {
                    handled++;
                }
            } catch (Exception e) {
                log.error("处理暂离超时失败: reservationId={}", reservation.getId(), e);
            }
        }
        return handled;
    }

    /**
     * 单条处理，故意<b>不加事务</b>：每条订单自己提交，
     * 某一条失败不会把同一轮里已经处理好的其他订单一起回滚。
     *
     * @return false 表示这条订单已经被学生自己操作流转走了，CAS 失败直接跳过
     */
    private boolean timeoutReserved(Reservation reservation) {
        if (reservationMapper.compareAndSetStatus(reservation.getId(),
                ReservationStatus.RESERVED.name(), ReservationStatus.TIMEOUT.name()) != 1) {
            return false;
        }
        reservation.setStatus(ReservationStatus.TIMEOUT.name());
        violationService.create(reservation.getUserId(), reservation.getSeatId(),
                ViolationType.RESERVATION_TIMEOUT,
                String.format("预约后 %d 分钟内未刷卡签到，系统自动释放座位",
                        seatProperties.getReserveTimeoutMinutes()));
        releaseSeat(reservation.getSeatId(), SeatStatus.RESERVED);
        log.info("预约超时自动释放: reservationId={}, seatId={}",
                reservation.getId(), reservation.getSeatId());
        return true;
    }

    private boolean timeoutAway(Reservation reservation) {
        if (reservationMapper.compareAndSetStatus(reservation.getId(),
                ReservationStatus.AWAY.name(), ReservationStatus.COMPLETED.name()) != 1) {
            return false;
        }
        LocalDateTime now = TimeUtil.now();
        reservation.setStatus(ReservationStatus.COMPLETED.name());
        reservation.setReleaseTime(now);
        reservationMapper.update(null, Wrappers.<Reservation>lambdaUpdate()
                .set(Reservation::getReleaseTime, now)
                .eq(Reservation::getId, reservation.getId()));

        // 人确实来学过，时长照算（§23：暂离不拆分，按整个 USING 周期）
        int minutes = studyRecordService.createFromReservation(reservation);
        violationService.create(reservation.getUserId(), reservation.getSeatId(),
                ViolationType.AWAY_TIMEOUT,
                String.format("暂离超过 %d 分钟未回座，系统自动释放座位，本次学习 %d 分钟",
                        seatProperties.getAwayTimeoutMinutes(), minutes));
        releaseSeat(reservation.getSeatId(), SeatStatus.AWAY);
        log.info("暂离超时自动释放: reservationId={}, seatId={}, 学习 {} 分钟",
                reservation.getId(), reservation.getSeatId(), minutes);
        return true;
    }

    /* ------------------------------------------------------------
     * 内部工具
     * ------------------------------------------------------------ */

    /**
     * 订单结束、座位交回。CAS 失败只记日志不抛异常：
     * 常见原因是座位正处于 ALARM（假占座是物理事实，订单结束不代表座位空了，
     * 告警解除时 SeatService 会按订单还原，那时订单已经没了，自然回到 FREE），
     * 或者座位已被别的流程改过。两种情况都不该让学生端收到一个错误。
     */
    private void releaseSeat(Long seatId, SeatStatus expected) {
        if (!seatService.updateStatusIf(seatId, expected, SeatStatus.FREE)) {
            log.warn("座位未从 {} 释放为 FREE，保留当前状态: seatId={}", expected, seatId);
        }
        Seat seat = seatMapper.selectById(seatId);
        if (seat != null) {
            deviceService.sendDisplayClear(seat.getDeviceId());
        }
    }

    /**
     * 所有订单状态跃迁的唯一入口。
     *
     * <p>CAS 失败说明定时任务或另一个请求已经动过这条订单了，
     * 这时候必须停下来 —— 继续写时间戳会把别人的结果盖掉，
     * 典型症状是订单变成 COMPLETED 但 study_record 写了两条。
     */
    private void transition(Reservation reservation, ReservationStatus from, ReservationStatus to) {
        if (from == null || to == null) {
            throw BusinessException.conflict("预约状态异常，请刷新后重试");
        }
        if (reservationMapper.compareAndSetStatus(reservation.getId(), from.name(), to.name()) != 1) {
            throw BusinessException.conflict("预约状态已变化，请刷新后重试");
        }
        reservation.setStatus(to.name());
    }

    /**
     * 取订单并校验归属。
     *
     * <p>越权时给 403 而不是 404：404 会让"改了别人的订单 ID"这种 bug
     * 看起来像"订单不存在"，学生反复重试也找不到原因。
     */
    private Reservation requireOwn(Long userId, Long reservationId) {
        if (reservationId == null) {
            throw BusinessException.badRequest("缺少预约ID");
        }
        Reservation reservation = reservationMapper.selectById(reservationId);
        if (reservation == null) {
            throw BusinessException.notFound("预约不存在: " + reservationId);
        }
        if (!reservation.getUserId().equals(userId)) {
            throw BusinessException.forbidden("无权操作该预约");
        }
        return reservation;
    }

    private static void requireStatus(Reservation reservation, ReservationStatus expected, String message) {
        if (ReservationStatus.parse(reservation.getStatus()) != expected) {
            throw BusinessException.conflict(message);
        }
    }

    private ReservationVO toVO(Reservation reservation) {
        return ReservationVO.of(reservation, seatMapper.selectById(reservation.getSeatId()));
    }

    /** 一次 IN 查询取回整页订单的座位，避免"我的预约"列表 N+1。 */
    private Map<Long, Seat> loadSeats(List<Reservation> rows) {
        Set<Long> seatIds = new HashSet<>();
        for (Reservation row : rows) {
            if (row.getSeatId() != null) {
                seatIds.add(row.getSeatId());
            }
        }
        if (seatIds.isEmpty()) {
            return Map.of();
        }
        List<Seat> seats = seatMapper.selectList(Wrappers.<Seat>lambdaQuery().in(Seat::getId, seatIds));
        Map<Long, Seat> map = new HashMap<>(seats.size() * 2);
        for (Seat seat : seats) {
            map.put(seat.getId(), seat);
        }
        return map;
    }
}
