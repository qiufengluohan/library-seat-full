package com.example.libraryseat.controller;

import com.example.libraryseat.common.Result;
import com.example.libraryseat.service.OneNetService;
import com.example.libraryseat.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * OneNET 上行推送入口（编码规范 §13 / §15）。整条链路里<b>唯一</b>由平台调用的接口：
 *
 * <pre>
 * STM32 → ESP8266 → MQTT → OneNET Studio → HTTP 推送 → 这里 → Service → MySQL → WebSocket → 两个前端
 * </pre>
 *
 * <h2>为什么收 JsonNode 而不是 DTO</h2>
 *
 * 规范 §15 定义的是简洁报文（Postman 联调用），OneNET Studio 真正推来的会套一层信封，
 * 属性值还可能被包成 {@code {"value": x, "time": t}}。<b>报文长什么样是本项目唯一
 * 没法在开发期验证的东西</b>，所以入口收原始 {@link JsonNode}，
 * 由 {@code OneNetService#normalize} 统一归一化成 {@code OneNetDataDTO}。
 * 真机联调发现字段取不到时，只改 normalize 一个方法，业务代码不动。
 *
 * <p>代价是请求体不是合法 JSON 时会抛 {@code HttpMessageNotReadableException} →
 * 400，这是想要的：格式都不对的数据必须让平台知道没收到。
 *
 * <h2>鉴权</h2>
 *
 * 不校验 JWT（平台不可能有），改由 {@code JwtInterceptor} 校验
 * {@code onenet.push-secret} 共享密钥。没有这层，任何人都能伪造一条
 * {@code alarm_flag=true} 把座位刷成告警，或者伪造一次刷卡替别人签到。
 *
 * <h2>响应体</h2>
 *
 * 平台只认 HTTP 状态码：2xx 算推送成功，其余会重试。这里返回统一的 Result 信封，
 * 如果联调时发现平台把成功的推送也标成失败，说明它要求特定的 ack 结构，
 * 那时改这一个类的返回值即可，Service 层不受影响。
 *
 * <p><b>两个端点都不抛业务异常</b>：处理不了的数据（座位查不到、卡没绑定）
 * 只记日志返回 200，否则平台会把同一条数据反复重推。
 */
@RestController
@RequestMapping("/api/onenet")
@RequiredArgsConstructor
public class OneNetController {

    private final OneNetService oneNetService;
    private final JsonUtil jsonUtil;
    /**
     * 设备数据推送（§15.1 属性上报 / §15.3 事件上报 / §15.4 心跳）。
     *
     * <p>一个入口吃下三种报文，靠字段有无区分，具体分流规则见
     * {@link OneNetService#handleDeviceData}。
     */
    @PostMapping("/device-data")
    public Result<Void> deviceData(@RequestBody JsonNode payload) {
        oneNetService.handleDeviceData(payload);
        return Result.success();
    }
    /**
     * OneNET 保存推送配置时发 GET，带 nonce / msg / signature 三个参数，
     * 要求把 msg 的值原样返回（5 秒内、HTTP 200）。多一个字符都会被判校验失败。
     */
    @GetMapping(value = "/device-data", produces = MediaType.TEXT_PLAIN_VALUE)
    public String probe(HttpServletRequest request) {
        String msg = request.getParameter("msg");
        return msg == null ? "" : msg;
    }

    /**
     * RFID 事件推送（§15.2）：{@code {"device_id":"READER_01","event":"rfid_scan",
     * "rfid_uid":"A1B2C3D4","timestamp":...}}。
     *
     * @return data=true 表示这次刷卡确实完成了一次签到；false 表示卡没绑定、
     *         该学生没有待签到的订单、或处理过程中出错（都只记日志）。
     *         <b>false 也返回 200</b> —— 有人拿卡在读卡器上划一下但不是来签到的，
     *         这不该让平台认为推送失败。
     */
    @PostMapping("/rfid-event")
    public Result<Boolean> rfidEvent(@RequestBody JsonNode payload) {
        boolean signed = oneNetService.handleRfidEvent(payload);
        return Result.success(signed);
    }
}
