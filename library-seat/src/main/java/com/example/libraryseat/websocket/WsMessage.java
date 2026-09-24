package com.example.libraryseat.websocket;

import com.example.libraryseat.util.TimeUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * WebSocket 广播消息。编码规范 §18 定义的几种类型共用这一个结构。
 *
 * <h2>为什么没有 RESERVATION_UPDATE（§18.4）</h2>
 *
 * 订单的每一次状态跃迁都必然伴随座位业务状态的变化
 * （RESERVED→1、USING→2、AWAY→3、终态→0），而 SEAT_UPDATE 带的字段
 * （status + alarm + online）是 RESERVATION_UPDATE（只有 status）的严格超集。
 * 两种都发只会让管理端对同一个座位合并两次同样的值。
 * 所以后端统一发 SEAT_UPDATE；管理端 {@code stores/seat.js} 里那个
 * RESERVATION_UPDATE 监听器永远不会触发，留着也没有副作用。
 *
 * <h2>{@code @JsonInclude(NON_NULL)} 是这个类的关键，不能删</h2>
 *
 * application.yml 里全局配的是 {@code default-property-inclusion: always}，
 * 意思是"字段为 null 也要序列化出来"。对 REST 接口这是对的
 * （管理端要区分"没有告警"和"字段不存在"），但对 WebSocket 是致命的：
 *
 * <p>管理端 {@code stores/seat.js} 的 patchSeat 是<b>按字段增量合并</b>的，
 * 它只过滤 {@code undefined}，<b>不过滤 {@code null}</b>：
 *
 * <pre>{@code
 * for (const [key, value] of Object.entries(fields)) {
 *   if (value !== undefined) changes[key] = value
 * }
 * seatMap.set(seatId, { ...existing, ...changes })
 * }</pre>
 *
 * 于是一条只带 online 的 DEVICE_STATUS 消息，如果序列化成
 * {@code {"type":"DEVICE_STATUS","seat_id":1,"status":null,"online":false}}，
 * 就会把该座位真实的 status 覆盖成 null，座位卡片立刻变成"未知状态"灰块。
 *
 * <p>加了这个注解后，未赋值的字段直接从 JSON 里消失，
 * 到 JS 侧就是 {@code undefined}，正好被上面的判断跳过。
 * 类级注解只覆盖本类，不影响 REST 的全局 always。
 *
 * <p>字段名靠全局 SNAKE_CASE 策略转成 seat_id 等，不要手写 @JsonProperty。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsMessage {

    public static final String TYPE_SEAT_UPDATE = "SEAT_UPDATE";
    public static final String TYPE_ALARM = "ALARM";
    public static final String TYPE_DEVICE_STATUS = "DEVICE_STATUS";

    private String type;

    private Long seatId;

    /** seat.status，业务状态数字 0-4（§5.1）。设备离线不是业务状态，不要塞进这里 */
    private Integer status;

    private Boolean alarm;

    private Boolean online;

    /** epoch <b>秒</b>，不是毫秒（§18 示例 1750000000）。REST 的时间是字符串，两者格式不同 */
    private Long timestamp;

    /* ------------------------------------------------------------
     * §18.1 ~ §18.3 三种消息。每种只填自己需要的字段，其余留 null。
     * ------------------------------------------------------------ */

    /** §18.1 座位变化：状态 + 告警 + 在线，一次给全 */
    public static WsMessage seatUpdate(Long seatId, Integer status, Boolean alarm, Boolean online) {
        WsMessage msg = base(TYPE_SEAT_UPDATE, seatId);
        msg.status = status;
        msg.alarm = alarm;
        msg.online = online;
        return msg;
    }

    /** §18.2 设备上下线。不带 status，避免覆盖业务状态 */
    public static WsMessage deviceStatus(Long seatId, Boolean online) {
        WsMessage msg = base(TYPE_DEVICE_STATUS, seatId);
        msg.online = online;
        return msg;
    }

    /** §18.3 告警。不带 status —— 管理端注释写明"业务 status 由后端另发 SEAT_UPDATE" */
    public static WsMessage alarm(Long seatId, Boolean alarm) {
        WsMessage msg = base(TYPE_ALARM, seatId);
        msg.alarm = alarm;
        return msg;
    }

    private static WsMessage base(String type, Long seatId) {
        WsMessage msg = new WsMessage();
        msg.type = type;
        msg.seatId = seatId;
        msg.timestamp = TimeUtil.toEpochSecondNow();
        return msg;
    }
}
