package com.example.libraryseat.service;

import com.example.libraryseat.dto.OneNetDataDTO;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * OneNET 上行专属代码。编码规范 §19 / §15，方案 §53。
 *
 * <p><b>下行不在这个接口里</b>，见 {@link OneNetDownlinkService} ——
 * 上下行放同一个 Bean 会让依赖成环（OneNetService → RfidService →
 * ReservationService → DeviceService → OneNetService），Spring Boot 3 直接启动失败。
 *
 * <h2>职责边界</h2>
 *
 * 接 OneNET 的 HTTP 推送，把<b>平台实际发来的报文</b>归一化成
 * {@link OneNetDataDTO}，再交给 SeatService / RfidService 走正常业务。
 * 这里不写 SQL、不改状态、不发广播，只做"翻译 + 分派"。
 *
 * <p>方案 §53「Web 想直接调用 OneNET：禁止」、编码规范 §42
 * 「前端绝对不能直接拼 OneNET 请求」—— api-key 和签名只存在于后端进程里。
 *
 * <h2>上行推送的鉴权</h2>
 *
 * {@code /api/onenet/**} 免 JWT（OneNET 不会带我们的 token），
 * 靠 {@code onenet.push-secret} 共享密钥校验，校验点在 {@code JwtInterceptor}。
 * 少了这道校验，任何人都能 POST 一条"设备上报"把座位刷成任意状态。
 */
public interface OneNetService {

    /* ------------------------------------------------------------
     * 物模型标识符（model-Wh08f3Q71p.json 里的原名）。
     *
     * 和 OperationLogService 的操作名常量同理，必须集中定义：
     * 属性名写错一个字母，OneNET 照样返回 200，设备端收不到任何东西，
     * 日志里也不会有报错 —— 现象只是"点了下发但设备没反应"，极难排查。
     * ------------------------------------------------------------ */

    /** 压力 ADC 阈值，rw，int32 0-4095。管理端"修改ADC阈值"下发它 */
    String PROP_ADC_THRESHOLD = "adc_threshold";

    /** 座位显示屏内容，rw，int32 0-255。释放座位时下发"显示恢复" */
    String PROP_SEAT_DISPLAY = "seat_display";

    /** 压力 ADC 实时读数，r */
    String PROP_PRESSURE_ADC = "pressure_adc";

    /** 人体红外状态，r。true = 有人 */
    String PROP_PIR_STATE = "pir_state";

    /** 设备本地判定的假占座告警，r */
    String PROP_ALARM_FLAG = "alarm_flag";

    /** 事件：假占座 */
    String EVENT_FAKE_OCCUPY = "fake_occupy";

    /** 事件：刷卡 */
    String EVENT_RFID_SCAN = "rfid_scan";

    /** 服务：蜂鸣器，sync，入参 duration_ms（0-65535） */
    String SERVICE_BUZZER_CTRL = "buzzer_ctrl";

    /** buzzer_ctrl 的入参名 */
    String PARAM_DURATION_MS = "duration_ms";

    /** 读卡器事件里的卡号字段名 */
    String PARAM_RFID_UID = "rfid_uid";

    /* ------------------------------------------------------------
     * 上行：OneNET → 后端
     * ------------------------------------------------------------ */

    /**
     * 处理设备数据推送（{@code POST /api/onenet/device-data}）。
     *
     * <p>一个入口吃下 §15.1 / §15.3 / §15.4 三种报文，靠字段有无区分：
     * <ul>
     *   <li>带 {@code event=rfid_scan} 或 {@code rfid_uid} → 先走签到</li>
     *   <li>带 {@code event=fake_occupy} 或 {@code alarm_flag=true} → 告警路径</li>
     *   <li>只带 pressure_adc / pir_state / online → 常规上报，只写影子表</li>
     * </ul>
     *
     * <p><b>关键约束：常规上报绝不改 seat.status。</b>
     * 业务状态只跟 reservation 走，压力和红外只是"设备看到了什么"，
     * 不是"这个座位处于什么业务阶段"（方案 §5、§53）。
     * 唯一的例外是告警：alarm_flag 翻成 true 时把 status 置为 ALARM(4)，
     * 因为小程序的红色闪烁是按 status===4 触发的。
     *
     * <p><b>"处理不了"和"这次没处理成"要分开</b>：报文本身有问题（device_id 查不到座位、
     * 字段缺失、结构不认识）时只记 warn 就返回，绝不抛 —— 抛出去就是 500，
     * OneNET 会反复重推一条我们永远处理不了的数据；但数据库连接不上这类临时故障
     * 要让它正常抛出去，平台重推一次就好了，吞掉反而丢数据。
     */
    void handleDeviceData(JsonNode payload);

    /**
     * 处理 RFID 事件推送（{@code POST /api/onenet/rfid-event}），§15.2。
     *
     * <p>报文体是 {@code {"device_id":"READER_01","event":"rfid_scan",
     * "rfid_uid":"A1B2C3D4","timestamp":...}}，转手交给
     * {@link RfidService#signInByUid(String)}。
     *
     * <p>READER_01 是共享读卡器，<b>它不绑定任何座位</b>，
     * 所以这里不要拿 device_id 去查 seat —— 查不到是正常的。
     * 座位从学生的 RESERVED 订单里来。
     *
     * @return true 表示这次刷卡确实完成了一次签到
     */
    boolean handleRfidEvent(JsonNode payload);

    /**
     * 把平台推来的原始报文归一化成 {@link OneNetDataDTO}。
     *
     * <p>之所以要单独暴露这个方法：报文长什么样是<b>本项目唯一没法在开发期验证的东西</b>。
     * 编码规范 §15 定义的是简洁格式（Postman 联调用），OneNET Studio 真正的
     * 数据推送会在外面套一层信封，属性值还可能被包成 {@code {"value": x, "time": t}}。
     * 真机联调时如果状态死活不更新，第一件事就是打开 DEBUG 日志看原始报文，
     * 然后照着它改这一个方法 —— 业务代码一行都不用动。
     *
     * <p>解析不出来时返回一个字段全空的 DTO，<b>不返回 null 也不抛异常</b>。
     */
    OneNetDataDTO normalize(JsonNode payload);
}
