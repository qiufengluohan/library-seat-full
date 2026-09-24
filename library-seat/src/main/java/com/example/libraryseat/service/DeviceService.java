package com.example.libraryseat.service;

import com.example.libraryseat.vo.DeviceVO;

import java.util.List;

/**
 * 设备列表 / 设备状态 / 设备参数。编码规范 §19。
 *
 * <p><b>这个 Service 是前端与 OneNET 之间唯一的通道。</b>
 * 方案 §53「Web 想直接调用 OneNET：禁止」、编码规范 §42
 * 「前端绝对不能直接拼 OneNET 请求」—— 管理端点"下发阈值"时，
 * 请求打到 {@code /api/admin/devices/{id}/config}，由这里翻译成 OneNET 属性下发。
 * api-key 永不出后端进程。
 *
 * <p>deviceId 一律是 OneNET 的 <b>device_name</b>（如 {@code SEAT_001}），
 * 不是控制台里那串数字 ID。Studio 的下行 API 就是按 product_id + device_name 寻址的。
 */
public interface DeviceService {

    /**
     * 设备列表（{@code GET /api/admin/devices}）。
     *
     * <p>数据来自 seat_shadow，<b>不是实时问 OneNET 要的</b>。
     * 方案 §20：影子表就是为了让管理端一次查询拿到全馆设备状态，
     * 不用为每个设备发一次云端请求（那样既慢又容易触发限流）。
     *
     * <p>返回数组，管理端 {@code DeviceManage.vue} 直接渲染表格。
     */
    List<DeviceVO> listDevices();

    /**
     * 修改 ADC 阈值（{@code POST /api/admin/devices/{id}/config}）。
     *
     * <p>翻译成 OneNET 属性下发 {@code {"adc_threshold": n}}，
     * 对应物模型里 accessMode=rw 的 adc_threshold（int32, 0-4095）。
     *
     * <p>下发是<b>尽力而为</b>的：失败只记日志，不让接口报错。
     * 理由见 {@link OneNetService}。但 operation_log 照样要写 ——
     * 管理员需要知道"我点过这个按钮"，至于设备有没有真的收到，
     * 看影子表里下一次上报的值就知道。
     */
    void updateAdcThreshold(String deviceId, Integer adcThreshold, Long adminId);

    /**
     * 蜂鸣器测试（{@code POST /api/admin/devices/{id}/buzzer}）。
     *
     * <p>翻译成 OneNET 服务调用 {@code buzzer_ctrl}，入参 {@code duration_ms}。
     * 物模型里这个 service 是 sync 调用，但我们<b>不等它的同步返回</b> ——
     * 设备可能离线，等下去会把管理端的请求一起挂住。
     */
    void testBuzzer(String deviceId, Integer durationMs, Long adminId);

    /**
     * 强制释放时下发"显示恢复"指令（编码规范 §14.9 第 7 步）。
     *
     * <p>写 seat_display 属性，值取 {@code onenet.display-clear-value}。
     * 由 ReservationService 调用，失败同样只记日志。
     */
    void sendDisplayClear(String deviceId);

    /**
     * 离线巡检（编码规范 §25），由 {@code task.DeviceOfflineTask} 每 60 秒调一次。
     *
     * <p>把 {@code last_report_at} 超过 {@code seat.device-offline-timeout-seconds}
     * 的影子行标记为 {@code online=0}，并对每一条广播一次 DEVICE_STATUS。
     *
     * <p><b>离线不是座位状态。</b>方案 §5.3 / §39：SeatStatus 只有
     * FREE / RESERVED / USING / AWAY / ALARM 五个，设备掉线不改
     * {@code seat.status}，只是在管理端和小程序上多加一个"离线"徽章 ——
     * 座位能不能用是业务事实（有没有人在用），设备通不通是运维事实，
     * 混在一起会出现"设备掉线导致学生的预约变成灰色"这种莫名其妙的表现。
     *
     * <p>所以这个方法只动 seat_shadow 和 WebSocket，<b>绝不碰 seat 表</b>，
     * 也不下发任何 OneNET 指令（设备都不上报了，下发也是白搭）。
     *
     * @return 本次真正被标记为离线的条数，供定时任务打一行汇总日志；
     *         设备在 SELECT 和 UPDATE 之间刚好上报过的不计入
     */
    int markOfflineDevices();
}
