package com.example.libraryseat.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.libraryseat.common.BusinessException;
import com.example.libraryseat.config.OneNetProperties;
import com.example.libraryseat.config.SeatProperties;
import com.example.libraryseat.entity.Seat;
import com.example.libraryseat.entity.SeatShadow;
import com.example.libraryseat.mapper.SeatMapper;
import com.example.libraryseat.mapper.SeatShadowMapper;
import com.example.libraryseat.service.DeviceService;
import com.example.libraryseat.service.OneNetDownlinkService;
import com.example.libraryseat.service.OneNetService;
import com.example.libraryseat.service.OperationLogService;
import com.example.libraryseat.service.WebSocketService;
import com.example.libraryseat.util.TimeUtil;
import com.example.libraryseat.vo.DeviceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private final SeatMapper seatMapper;
    private final SeatShadowMapper seatShadowMapper;
    private final OneNetDownlinkService oneNetDownlink;
    private final OperationLogService operationLogService;
    private final OneNetProperties oneNetProperties;
    private final SeatProperties seatProperties;
    private final WebSocketService webSocketService;

    @Override
    public List<DeviceVO> listDevices() {
        // 按座位表驱动：共享读卡器 READER_01 不占座位，本就不该出现在设备列表里
        List<Seat> seats = seatMapper.selectList(Wrappers.<Seat>lambdaQuery()
                .isNotNull(Seat::getDeviceId)
                .ne(Seat::getDeviceId, "")
                .orderByAsc(Seat::getSeatCode));
        if (seats.isEmpty()) {
            return List.of();
        }

        List<SeatShadow> shadows = seatShadowMapper.selectList(null);
        Map<Long, SeatShadow> bySeatId = new HashMap<>(shadows.size() * 2);
        for (SeatShadow shadow : shadows) {
            if (shadow.getSeatId() != null) {
                bySeatId.put(shadow.getSeatId(), shadow);
            }
        }

        List<DeviceVO> result = new ArrayList<>(seats.size());
        for (Seat seat : seats) {
            result.add(DeviceVO.of(seat, bySeatId.get(seat.getId())));
        }
        return result;
    }

    @Override
    public void updateAdcThreshold(String deviceId, Integer adcThreshold, Long adminId) {
        Seat seat = requireDeviceSeat(deviceId);
        if (adcThreshold == null) {
            throw BusinessException.badRequest("缺少阈值");
        }
        oneNetDownlink.setProperties(seat.getDeviceId(),
                Map.of(OneNetService.PROP_ADC_THRESHOLD, adcThreshold));
        operationLogService.record(adminId, OperationLogService.OP_UPDATE_THRESHOLD,
                seat.getDeviceId(),
                String.format("下发压力阈值 %d（座位 %s）", adcThreshold, seat.getSeatCode()));
        log.info("下发 ADC 阈值: deviceId={}, adcThreshold={}, adminId={}",
                seat.getDeviceId(), adcThreshold, adminId);
    }

    @Override
    public void testBuzzer(String deviceId, Integer durationMs, Long adminId) {
        Seat seat = requireDeviceSeat(deviceId);
        if (durationMs == null) {
            throw BusinessException.badRequest("缺少鸣响时长");
        }
        oneNetDownlink.invokeService(seat.getDeviceId(), OneNetService.SERVICE_BUZZER_CTRL,
                Map.of(OneNetService.PARAM_DURATION_MS, durationMs));
        operationLogService.record(adminId, OperationLogService.OP_TEST_BUZZER,
                seat.getDeviceId(),
                String.format("蜂鸣器鸣响 %d 毫秒（座位 %s）", durationMs, seat.getSeatCode()));
        log.info("下发蜂鸣器测试: deviceId={}, durationMs={}, adminId={}",
                seat.getDeviceId(), durationMs, adminId);
    }

    @Override
    public void sendDisplayClear(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        // 系统动作，不写 operation_log —— 那张表是给管理员的动作留痕的，
        // 每次正常离座都记一条会把真正的操作淹掉
        oneNetDownlink.setProperties(deviceId,
                Map.of(OneNetService.PROP_SEAT_DISPLAY, oneNetProperties.getDisplayClearValue()));
    }

    @Override
    public int markOfflineDevices() {
        LocalDateTime threshold = TimeUtil.now()
                .minusSeconds(seatProperties.getDeviceOfflineTimeoutSeconds());
        List<SeatShadow> stale = seatShadowMapper.listStaleOnline(threshold);
        if (stale.isEmpty()) {
            return 0;
        }

        int marked = 0;
        for (SeatShadow shadow : stale) {
            // 逐行条件更新，不批量：一条脏数据不该让整轮巡检失败，
            // 而且只有真正翻转成功的那些才需要广播（否则管理端会收到重复的离线消息）
            if (seatShadowMapper.markOfflineIfStale(shadow.getId(), threshold) == 0) {
                // SELECT 之后设备刚好上报了一条，它还活着，什么都不做
                continue;
            }
            marked++;
            webSocketService.broadcastDeviceStatus(shadow.getSeatId(), false);
        }

        if (marked > 0) {
            log.info("设备离线巡检: {} 台设备超过 {} 秒未上报，已标记离线",
                    marked, seatProperties.getDeviceOfflineTimeoutSeconds());
        }
        return marked;
    }

    /**
     * 路径参数 {deviceId} 是 OneNET 设备名（SEAT_001）。
     * 找不到就 404：管理端是拿着设备列表里的值来调的，
     * 报 404 说明列表已经过期或者库里的 device_id 被改过。
     */
    private Seat requireDeviceSeat(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw BusinessException.badRequest("缺少设备ID");
        }
        Seat seat = seatMapper.selectByDeviceId(deviceId.trim());
        if (seat == null) {
            throw BusinessException.notFound("设备不存在: " + deviceId);
        }
        return seat;
    }
}
