package com.example.libraryseat.service.impl;

import com.example.libraryseat.entity.Seat;
import com.example.libraryseat.entity.SeatShadow;
import com.example.libraryseat.mapper.SeatMapper;
import com.example.libraryseat.mapper.SeatShadowMapper;
import com.example.libraryseat.service.WebSocketService;
import com.example.libraryseat.websocket.SeatWebSocketHandler;
import com.example.libraryseat.websocket.WsMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketServiceImpl implements WebSocketService {

    private final SeatMapper seatMapper;
    private final SeatShadowMapper seatShadowMapper;
    private final SeatWebSocketHandler handler;

    @Override
    public void broadcastSeatUpdate(Long seatId) {
        if (seatId == null) {
            return;
        }
        Seat seat = seatMapper.selectById(seatId);
        if (seat == null) {
            log.warn("广播 SEAT_UPDATE 时座位不存在，已跳过: seatId={}", seatId);
            return;
        }
        SeatShadow shadow = seatShadowMapper.findBySeatId(seatId);
        handler.broadcast(WsMessage.seatUpdate(
                seatId,
                seat.getStatus(),
                isOn(shadow == null ? null : shadow.getAlarmFlag()),
                isOn(shadow == null ? null : shadow.getOnline())));
    }

    @Override
    public void broadcastAlarm(Long seatId, boolean alarm) {
        if (seatId == null) {
            return;
        }
        handler.broadcast(WsMessage.alarm(seatId, alarm));
    }

    @Override
    public void broadcastDeviceStatus(Long seatId, boolean online) {
        if (seatId == null) {
            return;
        }
        handler.broadcast(WsMessage.deviceStatus(seatId, online));
    }

    @Override
    public void broadcast(WsMessage message) {
        handler.broadcast(message);
    }

    /**
     * seat_shadow 的三个标志位列是 TINYINT，实体里用 Integer 存（见 SeatShadow 的注释）。
     * 只有明确的 1 才算 true；null（影子行还没建）和 0 都是 false ——
     * 从没上报过的设备不能显示成"在线且无告警"。
     */
    private static boolean isOn(Integer flag) {
        return Integer.valueOf(1).equals(flag);
    }
}
