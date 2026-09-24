package com.example.libraryseat.dto;

import lombok.Data;

/**
 * OneNET 设备上报入参，编码规范 §10.6 / §15.1。
 *
 * <p>这是<b>规范定义的简洁格式</b>，也是归一化之后的内部表示：OneNetController 收的是
 * 原始 {@code JsonNode}（Studio 推送的信封结构和 Postman 联调用的简洁格式不一致，
 * 不能直接反序列化），由 {@code OneNetService#normalize} 转成这个 DTO 之后，
 * 后面所有业务代码只认这一种。
 *
 * <p>所有字段都可为空：心跳报文只有 device_id + online（§15.4），
 * 属性上报可能只带变化了的那几个字段。判空逻辑在 Service，不在这里加校验注解。
 */
@Data
public class OneNetDataDTO {

    /** 设备名称，如 SEAT_001 / READER_01。 */
    private String deviceId;

    /** 规范里是字符串（设备端好拼），后端转成 Long。 */
    private String seatId;

    private Integer pressureAdc;

    private Boolean pirState;

    private Boolean alarmFlag;

    /** 秒或毫秒都接受，见 TimeUtil#fromEpoch。 */
    private Long timestamp;

    /** 事件标识：rfid_scan / fake_occupy；普通属性上报为 null。 */
    private String event;

    /** rfid_scan 事件携带。 */
    private String rfidUid;

    /** 心跳报文携带；属性上报不带，由后端一律视为在线。 */
    private Boolean online;
}
