package com.example.libraryseat.service;

import java.util.Map;

/**
 * OneNET 下行传输层：属性下发与服务调用。
 *
 * <h2>为什么要和 {@link OneNetService} 分开</h2>
 *
 * 如果上行和下行放在同一个 Bean 里，依赖会成环，Spring Boot 3 默认直接启动失败：
 * <pre>
 * OneNetService(上行) → RfidService → ReservationService → DeviceService → OneNetService(下行)
 * </pre>
 * 拆成两个之后，上行那个 Bean 只被 Controller 依赖，下行这个 Bean 不依赖任何业务 Service，
 * 环就断了。职责上也更清楚：这里只管"把一个 Map 变成一次带签名的 HTTP POST"，
 * 完全不知道座位、订单是什么。
 *
 * <h2>失败为什么一律只记日志</h2>
 *
 * 管理员点"强制释放"，主流程是关订单 → 座位 FREE → 写日志 → 广播。
 * 下发只是顺便告诉设备把 OLED 恢复正常。设备可能离线、云端可能限流、网络可能超时，
 * 这些都<b>不该让座位卡在 USING 释放不掉</b>。数据库永远是事实来源，
 * 设备下一次上报会把真实状态带回来，影子表随之对齐。
 *
 * <p>所以两个方法都是 {@code @Async} + 内部吞掉所有异常。
 */
public interface OneNetDownlinkService {

    /**
     * 属性下发，对应物模型里 accessMode=rw 的 {@code adc_threshold} / {@code seat_display}。
     *
     * @param deviceName OneNET 设备名（SEAT_001），不是控制台里那串数字 ID
     * @param properties 属性名 → 值，一次可以下发多个
     */
    void setProperties(String deviceName, Map<String, Object> properties);

    /**
     * 服务调用，对应物模型里的 {@code buzzer_ctrl}。
     *
     * <p>物模型把 buzzer_ctrl 标成 sync，但这里按 async 发且<b>不等返回</b>：
     * 设备离线时同步调用会一直挂到读超时，把管理员的请求线程一起拖住。
     *
     * @param params 服务入参，无入参的服务传 null
     */
    void invokeService(String deviceName, String serviceId, Map<String, Object> params);

    /**
     * product-id 和 api-key 是否都配齐了。
     *
     * <p>没配齐时上行照样能收（OneNET 推送不校验我们的 key），
     * 但所有下行都会失败 —— 启动时会打一条 WARN 说明这件事。
     */
    boolean isConfigured();
}
