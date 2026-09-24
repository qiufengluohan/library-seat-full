package com.example.libraryseat.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.libraryseat.common.BusinessException;
import com.example.libraryseat.dto.OneNetDataDTO;
import com.example.libraryseat.entity.Reservation;
import com.example.libraryseat.entity.Seat;
import com.example.libraryseat.entity.SeatShadow;
import com.example.libraryseat.entity.User;
import com.example.libraryseat.enums.ReservationStatus;
import com.example.libraryseat.enums.SeatStatus;
import com.example.libraryseat.enums.ViolationType;
import com.example.libraryseat.mapper.ReservationMapper;
import com.example.libraryseat.mapper.SeatMapper;
import com.example.libraryseat.mapper.SeatShadowMapper;
import com.example.libraryseat.mapper.UserMapper;
import com.example.libraryseat.service.DeviceService;
import com.example.libraryseat.service.OperationLogService;
import com.example.libraryseat.service.SeatService;
import com.example.libraryseat.service.ViolationService;
import com.example.libraryseat.service.WebSocketService;
import com.example.libraryseat.util.TimeUtil;
import com.example.libraryseat.vo.SeatDetailVO;
import com.example.libraryseat.vo.SeatVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatServiceImpl implements SeatService {

    private final SeatMapper seatMapper;
    private final SeatShadowMapper seatShadowMapper;
    private final ReservationMapper reservationMapper;
    private final UserMapper userMapper;
    private final WebSocketService webSocketService;
    private final ViolationService violationService;
    private final OperationLogService operationLogService;
    private final DeviceService deviceService;

    /* ------------------------------------------------------------
     * 查询
     * ------------------------------------------------------------ */

    @Override
    public List<SeatVO> listSeats() {
        List<Seat> seats = listSeatEntities();
        Map<Long, SeatShadow> shadows = loadShadowsBySeatId();

        List<SeatVO> result = new ArrayList<>(seats.size());
        for (Seat seat : seats) {
            result.add(SeatVO.of(seat, shadows.get(seat.getId())));
        }
        return result;
    }

    @Override
    public List<SeatVO> listSeatsForAdmin() {
        List<Seat> seats = listSeatEntities();
        if (seats.isEmpty()) {
            return List.of();
        }
        Map<Long, SeatShadow> shadows = loadShadowsBySeatId();
        Map<Long, Reservation> actives = loadActiveReservationsBySeatId();
        Map<Long, String> nicknames = loadNicknames(actives.values());

        List<SeatVO> result = new ArrayList<>(seats.size());
        for (Seat seat : seats) {
            SeatVO vo = SeatVO.of(seat, shadows.get(seat.getId()));
            Reservation current = actives.get(seat.getId());
            if (current != null) {
                vo.setStudentName(nicknames.get(current.getUserId()));
                vo.setReserveTime(current.getReserveTime());
                vo.setSignTime(current.getSignTime());
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    public SeatDetailVO getDetail(Long seatId) {
        Seat seat = requireSeat(seatId);
        SeatShadow shadow = seatShadowMapper.findBySeatId(seatId);
        Reservation current = reservationMapper.findCurrentBySeatId(seatId);
        return SeatDetailVO.of(seat, shadow, current, studyMinutesOf(current));
    }

    @Override
    public Seat requireSeat(Long seatId) {
        if (seatId == null) {
            throw BusinessException.badRequest("缺少座位ID");
        }
        Seat seat = seatMapper.selectById(seatId);
        if (seat == null) {
            throw BusinessException.notFound("座位不存在: " + seatId);
        }
        return seat;
    }

    @Override
    public Seat findByDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        return seatMapper.selectByDeviceId(deviceId.trim());
    }

    /* ------------------------------------------------------------
     * 状态流转
     * ------------------------------------------------------------ */

    @Override
    public void updateStatus(Long seatId, SeatStatus status) {
        if (seatId == null || status == null) {
            return;
        }
        seatMapper.update(null, Wrappers.<Seat>lambdaUpdate()
                .set(Seat::getStatus, status.getCode())
                .eq(Seat::getId, seatId));
        webSocketService.broadcastSeatUpdate(seatId);
    }

    @Override
    public boolean updateStatusIf(Long seatId, SeatStatus expected, SeatStatus next) {
        if (seatId == null || expected == null || next == null) {
            return false;
        }
        boolean won = seatMapper.compareAndSetStatus(seatId, expected.getCode(), next.getCode()) == 1;
        if (won) {
            webSocketService.broadcastSeatUpdate(seatId);
        }
        return won;
    }

    @Override
    public SeatStatus statusFromReservation(Long seatId) {
        Reservation current = reservationMapper.findCurrentBySeatId(seatId);
        if (current == null) {
            return SeatStatus.FREE;
        }
        return SeatStatus.fromReservation(ReservationStatus.parse(current.getStatus()));
    }

    /* ------------------------------------------------------------
     * 设备上报
     * ------------------------------------------------------------ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyDeviceReport(OneNetDataDTO dto) {
        if (dto == null || dto.getDeviceId() == null || dto.getDeviceId().isBlank()) {
            log.warn("设备上报缺少 device_id，已忽略: {}", dto);
            return;
        }
        Seat seat = findByDeviceId(dto.getDeviceId());
        if (seat == null) {
            // 共享读卡器 READER_01 的 rfid_scan 就走这里，属于正常情况。
            // 抛异常的话 OneNET 会收到 500 并把这条数据反复重推。
            // 刻意不用报文里的 seat_id 兜底查座位：那是设备自己填的，
            // 一旦填错就会把 A 座的数据写进 B 座的影子表，比丢一条上报严重得多。
            log.warn("device_id 没有对应座位，已忽略上报: deviceId={}, seatId={}",
                    dto.getDeviceId(), dto.getSeatId());
            return;
        }

        SeatShadow shadow = seatShadowMapper.findByDeviceId(seat.getDeviceId());
        boolean isNew = shadow == null;
        if (isNew) {
            shadow = new SeatShadow();
            shadow.setSeatId(seat.getId());
            shadow.setDeviceId(seat.getDeviceId());
            shadow.setOnline(0);
            shadow.setAlarmFlag(0);
        }
        Integer alarmBefore = shadow.getAlarmFlag();
        Integer onlineBefore = shadow.getOnline();

        mergeReport(shadow, dto);
        persistShadow(shadow, isNew);

        boolean alarmNow = isOn(shadow.getAlarmFlag());
        boolean onlineNow = isOn(shadow.getOnline());

        if (onlineNow != isOn(onlineBefore)) {
            webSocketService.broadcastDeviceStatus(seat.getId(), onlineNow);
            log.info("设备在线状态变化: deviceId={}, online={}", seat.getDeviceId(), onlineNow);
        }
        if (alarmNow != isOn(alarmBefore)) {
            onAlarmFlip(seat, dto, alarmNow);
        }
    }

    /**
     * 只覆盖报文里确实带了的字段。心跳不带 pressure_adc，
     * 无脑 set 会把上一次的读数抹成 null，管理端就会显示"压力值 -"。
     */
    private static void mergeReport(SeatShadow shadow, OneNetDataDTO dto) {
        if (dto.getPressureAdc() != null) {
            shadow.setPressureAdc(dto.getPressureAdc());
        }
        if (dto.getPirState() != null) {
            shadow.setPirState(dto.getPirState() ? 1 : 0);
        }
        if (dto.getAlarmFlag() != null) {
            shadow.setAlarmFlag(dto.getAlarmFlag() ? 1 : 0);
        }
        // online 缺省视为在线：属性上报不带这个字段，收到报文本身就证明设备活着。
        // 显式带 false 的是 OneNET 的设备状态通知，那种要认。
        shadow.setOnline(dto.getOnline() == null || dto.getOnline() ? 1 : 0);

        LocalDateTime reportedAt = TimeUtil.fromEpoch(dto.getTimestamp());
        shadow.setLastReportAt(reportedAt != null ? reportedAt : TimeUtil.now());
    }

    /**
     * 影子行的建/改。seat_id 和 device_id 都是 UNIQUE，
     * 两个设备同时首报同一个座位时会撞唯一键，撞上就退化成更新。
     */
    private void persistShadow(SeatShadow shadow, boolean isNew) {
        if (!isNew) {
            seatShadowMapper.updateById(shadow);
            return;
        }
        try {
            seatShadowMapper.insert(shadow);
        } catch (DuplicateKeyException e) {
            SeatShadow existing = seatShadowMapper.findByDeviceId(shadow.getDeviceId());
            if (existing == null) {
                throw e;
            }
            shadow.setId(existing.getId());
            seatShadowMapper.updateById(shadow);
        }
    }

    /**
     * alarm_flag 翻转时的业务联动。只在翻转时动作：设备每 60 秒上报一次，
     * 每次都联动就会每分钟多记一条违规、多广播一遍。
     *
     * <p>常规上报<b>绝不改 seat.status</b>，这里是唯一的例外。
     */
    private void onAlarmFlip(Seat seat, OneNetDataDTO dto, boolean alarmNow) {
        Long seatId = seat.getId();
        if (!alarmNow) {
            // 告警解除：按订单还原业务状态，不需要额外存"告警前状态"
            SeatStatus restored = statusFromReservation(seatId);
            updateStatus(seatId, restored);
            webSocketService.broadcastAlarm(seatId, false);
            log.info("座位告警解除: seatCode={}, 状态还原为 {}",
                    seat.getSeatCode(), restored.getLabel());
            return;
        }

        Reservation current = reservationMapper.findCurrentBySeatId(seatId);
        updateStatus(seatId, SeatStatus.ALARM);
        violationService.create(
                current == null ? null : current.getUserId(),
                seatId,
                ViolationType.FAKE_OCCUPY,
                fakeOccupyDescription(seat, dto));
        webSocketService.broadcastAlarm(seatId, true);
        log.warn("座位假占座告警: seatCode={}, userId={}", seat.getSeatCode(),
                current == null ? null : current.getUserId());
    }

    /**
     * 描述会原样显示在管理端违规表格里，所以带上当时的传感器读数 ——
     * 答辩时能直接说明"为什么判定为假占座"。列宽 VARCHAR(255)，足够。
     */
    private static String fakeOccupyDescription(Seat seat, OneNetDataDTO dto) {
        String pressure = dto.getPressureAdc() == null ? "未知" : dto.getPressureAdc().toString();
        String pir = dto.getPirState() == null ? "未知" : (dto.getPirState() ? "有人" : "无人");
        return String.format("座位 %s 设备判定假占座（压力值 %s，红外 %s）",
                seat.getSeatCode(), pressure, pir);
    }

    /* ------------------------------------------------------------
     * 管理员消除告警
     * ------------------------------------------------------------ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearAlarm(Long seatId, Long adminId) {
        Seat seat = requireSeat(seatId);
        SeatShadow shadow = seatShadowMapper.findBySeatId(seatId);

        boolean alarming = SeatStatus.ALARM.getCode() == nz(seat.getStatus())
                || (shadow != null && isOn(shadow.getAlarmFlag()));
        if (!alarming) {
            // 网格数据是缓存的，管理员可能点了两次，或者告警在他点之前就已经自己消了。
            // 用 409：管理端 request.js 对 409 静默处理并刷新，不弹红条。
            throw BusinessException.conflict("该座位当前没有告警");
        }

        if (shadow != null && isOn(shadow.getAlarmFlag())) {
            shadow.setAlarmFlag(0);
            seatShadowMapper.updateById(shadow);
        }

        // 先写影子再改状态，updateStatus 的广播是现查库的，
        // 这样 SEAT_UPDATE 里的 alarm 就已经是 false，前端一次合并就对
        SeatStatus restored = statusFromReservation(seatId);
        updateStatus(seatId, restored);

        operationLogService.record(adminId, OperationLogService.OP_CLEAR_ALARM,
                seat.getSeatCode(), "告警已消除，座位状态还原为 " + restored.getLabel());
        deviceService.sendDisplayClear(seat.getDeviceId());
    }

    /* ------------------------------------------------------------
     * 内部工具
     * ------------------------------------------------------------ */

    private List<Seat> listSeatEntities() {
        return seatMapper.selectList(Wrappers.<Seat>lambdaQuery()
                .orderByAsc(Seat::getArea)
                .orderByAsc(Seat::getFloor)
                .orderByAsc(Seat::getSeatCode));
    }

    private Map<Long, SeatShadow> loadShadowsBySeatId() {
        List<SeatShadow> shadows = seatShadowMapper.selectList(null);
        Map<Long, SeatShadow> map = new HashMap<>(shadows.size() * 2);
        for (SeatShadow shadow : shadows) {
            if (shadow.getSeatId() != null) {
                map.put(shadow.getSeatId(), shadow);
            }
        }
        return map;
    }

    /**
     * 一次查完全馆进行中的订单。
     * 按 id 倒序 + putIfAbsent，脏数据下同一个座位有两条 active 时取最新那条。
     */
    private Map<Long, Reservation> loadActiveReservationsBySeatId() {
        List<Reservation> actives = reservationMapper.selectList(
                Wrappers.<Reservation>lambdaQuery()
                        .in(Reservation::getStatus,
                                ReservationMapper.names(ReservationStatus.activeSet()))
                        .orderByDesc(Reservation::getId));
        Map<Long, Reservation> map = new HashMap<>(actives.size() * 2);
        for (Reservation reservation : actives) {
            if (reservation.getSeatId() != null) {
                map.putIfAbsent(reservation.getSeatId(), reservation);
            }
        }
        return map;
    }

    /**
     * 批量取昵称，避免"一个座位一次 user 查询"的 N+1。
     * 昵称为空的用户不放进去 —— 管理端拿到 null 会退回显示 user_id，
     * 别在这里编一个"微信用户"顶替。
     */
    private Map<Long, String> loadNicknames(Collection<Reservation> reservations) {
        Set<Long> userIds = new HashSet<>();
        for (Reservation reservation : reservations) {
            if (reservation.getUserId() != null) {
                userIds.add(reservation.getUserId());
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<User> users = userMapper.selectList(Wrappers.<User>lambdaQuery().in(User::getId, userIds));
        Map<Long, String> names = new HashMap<>(users.size() * 2);
        for (User user : users) {
            if (user.getNickname() != null && !user.getNickname().isBlank()) {
                names.put(user.getId(), user.getNickname());
            }
        }
        return names;
    }

    /**
     * 本次进行中订单已学习的分钟数。未签到（RESERVED）时为 0。
     *
     * <p>按 §23 的口径实时算，不写库：暂离阶段不拆分，
     * 所以 AWAY 状态下也在继续累计，和最终落 study_record 的算法保持一致。
     */
    private static int studyMinutesOf(Reservation current) {
        if (current == null || current.getSignTime() == null) {
            return 0;
        }
        return TimeUtil.minutesBetween(current.getSignTime(), TimeUtil.now());
    }

    private static boolean isOn(Integer flag) {
        return Integer.valueOf(1).equals(flag);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
