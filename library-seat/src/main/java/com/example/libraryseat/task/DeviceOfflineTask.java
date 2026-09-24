package com.example.libraryseat.task;

import com.example.libraryseat.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 设备离线巡检。编码规范 §25。
 *
 * <p>{@code now - last_report_at > OFFLINE_TIMEOUT} 就把影子表标记为离线并广播
 * DEVICE_STATUS。阈值默认 120 秒，配在 {@code seat.device-offline-timeout-seconds}：
 * 设备 60 秒一次心跳，120 秒等于<b>允许丢一次包</b>再判离线，
 * 否则宿舍楼 WiFi 抖一下就满屏灰色徽章。
 *
 * <p><b>离线不是座位状态</b>（方案 §5.3 / §39）：这个任务只改
 * {@code seat_shadow.online}，绝不碰 {@code seat.status}，也不碰订单。
 * 设备掉线时学生的预约仍然是"使用中"，管理端只是多显示一个离线标记。
 *
 * <p>逻辑全在 {@link DeviceService#markOfflineDevices()} 里（包括并发保护和广播），
 * 这个类只负责"每 60 秒叫它一次"，所以没有日志 —— Service 那边只在真的
 * 标了离线时才打一行，这里再打一遍就是每分钟两条噪音。
 */
@Component
@RequiredArgsConstructor
public class DeviceOfflineTask {

    /** 60 秒：与心跳周期一致，最坏情况掉线后 60+120 秒内被标记出来。 */
    private static final long FIXED_DELAY_MS = 60_000L;

    private final DeviceService deviceService;

    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void sweep() {
        deviceService.markOfflineDevices();
    }
}
