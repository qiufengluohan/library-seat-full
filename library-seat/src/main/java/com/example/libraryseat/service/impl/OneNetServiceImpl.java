package com.example.libraryseat.service.impl;

import com.example.libraryseat.config.OneNetProperties;
import com.example.libraryseat.dto.OneNetDataDTO;
import com.example.libraryseat.service.OneNetService;
import com.example.libraryseat.service.RfidService;
import com.example.libraryseat.service.SeatService;
import com.example.libraryseat.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OneNetServiceImpl implements OneNetService {

    /** Studio 推送可能把属性套在这些字段里，规范 §15 的简洁格式则是平铺在顶层 */
    private static final String[] PROPERTY_CONTAINERS = {"params", "data", "body", "properties"};

    private final SeatService seatService;
    private final RfidService rfidService;
    private final OneNetProperties oneNetProperties;
    private final JsonUtil jsonUtil;

    @PostConstruct
    void reportConfiguration() {
        if (oneNetProperties.isPushSecretConfigured()) {
            return;
        }
        log.warn("""
                onenet.push-secret 未设置，/api/onenet/** 目前处于无鉴权状态。\
                开发期用 Postman 联调可以这样，但部署到公网前必须设置 ONENET_PUSH_SECRET，\
                否则任何人都能伪造一条"设备上报"把座位状态刷成任意值。""");
    }

    @Override
    public void handleDeviceData(JsonNode payload) {
        OneNetDataDTO dto = normalize(payload);
        boolean rfidScan = isRfidScan(dto);
        if (rfidScan) {
            boolean signedIn = rfidService.signInByUid(dto.getRfidUid());
            log.info("上行刷卡事件: deviceId={}, rfidUid={}, 签到成功={}",
                    dto.getDeviceId(), dto.getRfidUid(), signedIn);
        }
        // 纯刷卡报文来自共享读卡器 READER_01，它不对应任何座位，
        // 交给 applyDeviceReport 只会多打一条没意义的 warn
        if (!rfidScan || hasSensorData(dto)) {
            seatService.applyDeviceReport(dto);
        }
    }

    @Override
    public boolean handleRfidEvent(JsonNode payload) {
        OneNetDataDTO dto = normalize(payload);
        if (dto.getRfidUid() == null || dto.getRfidUid().isBlank()) {
            log.warn("RFID 事件里没有 {}，已忽略", PARAM_RFID_UID);
            return false;
        }
        boolean signedIn = rfidService.signInByUid(dto.getRfidUid());
        log.info("RFID 刷卡: deviceId={}, rfidUid={}, 签到成功={}",
                dto.getDeviceId(), dto.getRfidUid(), signedIn);
        return signedIn;
    }

    /* ------------------------------------------------------------
     * 报文归一化
     * ------------------------------------------------------------ */

    @Override
    public OneNetDataDTO normalize(JsonNode payload) {
        OneNetDataDTO dto = new OneNetDataDTO();
        if (payload == null || !payload.isObject()) {
            log.warn("OneNET 上行报文为空或不是 JSON 对象，已忽略");
            return dto;
        }
        if (log.isDebugEnabled()) {
            // 真机联调时第一件事就是打开这行日志看平台到底发了什么，
            // 然后照着改本方法。别的地方都不用动。
            log.debug("OneNET 上行原始报文: {}", payload);
        }

        payload = unwrapEnvelope(payload);

        JsonNode scope = firstObject(payload, PROPERTY_CONTAINERS);
        if (scope == null) {
            scope = payload;
        }
        // OneNET 的 data 里面还套了一层 params，属性值在那里面
        JsonNode nestedParams = firstObject(scope, "params");
        if (nestedParams != null) {
            scope = nestedParams;
        }

        String deviceId = text(payload, "device_name", "device_id", "devName", "deviceId", "deviceName");
        if (deviceId == null) {
            deviceId = text(payload.get("device"), "device_name", "device_id", "name");
        }
        if (deviceId == null) {
            // §15.3 的 fake_occupy 事件把 device_id 放在输出参数里，不在信封上
            deviceId = text(scope, "device_id", "device_name");
        }
        dto.setDeviceId(deviceId);

        String seatId = text(payload, "seat_id", "seatId");
        dto.setSeatId(seatId != null ? seatId : text(scope, "seat_id", "seatId"));

        dto.setPressureAdc(asInt(scope, PROP_PRESSURE_ADC));
        dto.setPirState(asBool(scope, PROP_PIR_STATE));
        dto.setAlarmFlag(asBool(scope, PROP_ALARM_FLAG));
        dto.setOnline(asBool(payload, "online"));

        Long timestamp = asLong(payload, "timestamp", "time", "ts");
        dto.setTimestamp(timestamp != null ? timestamp : asLong(scope, "timestamp", "time", "ts"));

        String rfidUid = text(scope, PARAM_RFID_UID, "rfidUid", "uid");
        dto.setRfidUid(rfidUid != null ? rfidUid : text(payload, PARAM_RFID_UID, "rfidUid", "uid"));

        dto.setEvent(eventName(payload, scope));
        if (EVENT_FAKE_OCCUPY.equalsIgnoreCase(dto.getEvent()) && dto.getAlarmFlag() == null) {
            // 事件本身就代表"设备判定为假占座"，报文里不一定再带一遍 alarm_flag。
            // 补上它，告警联动才走得到 SeatService 那个统一的翻转判断里。
            dto.setAlarmFlag(Boolean.TRUE);
        }
        return dto;
    }
    /**
     * OneNET 全局推送把业务报文当成【字符串】塞在 msg 字段里，外面套着
     * signature / nonce / time / id。不解开这一层，deviceName 和 data.params 都够不到。
     *
     * <p>编码规范 §15 的简洁格式没有 msg 字段，那种原样返回，两种都吃得下。
     */
    private JsonNode unwrapEnvelope(JsonNode payload) {
        JsonNode msg = payload.get("msg");
        if (msg == null || !msg.isTextual()) {
            return payload;
        }
        JsonNode inner = jsonUtil.readTree(msg.asText());
        if (inner == null || !inner.isObject()) {
            log.warn("推送信封里的 msg 不是合法 JSON 对象，按原报文处理");
            return payload;
        }
        return inner;
    }
    /**
     * 只把已知的两个事件名当事件。
     *
     * <p>Studio 的推送用 {@code identifier} 标标识符，但它也可能是普通属性名
     * （{@code pressure_adc}）或者一串消息 ID。不做白名单的话，
     * 一次普通属性上报会被误判成事件，进而走进签到分支。
     */
    private static String eventName(JsonNode payload, JsonNode scope) {
        String event = text(payload, "event", "eventId", "service_id");
        if (event != null) {
            return event;
        }
        String identifier = text(payload, "identifier");
        if (EVENT_FAKE_OCCUPY.equalsIgnoreCase(identifier) || EVENT_RFID_SCAN.equalsIgnoreCase(identifier)) {
            return identifier;
        }
        return text(scope, "event");
    }

    private static boolean isRfidScan(OneNetDataDTO dto) {
        return EVENT_RFID_SCAN.equalsIgnoreCase(dto.getEvent())
                || (dto.getRfidUid() != null && !dto.getRfidUid().isBlank());
    }

    private static boolean hasSensorData(OneNetDataDTO dto) {
        return dto.getPressureAdc() != null || dto.getPirState() != null
                || dto.getAlarmFlag() != null || dto.getOnline() != null;
    }

    /* ------------------------------------------------------------
     * JsonNode 取值。
     *
     * 之所以写得这么"什么都认"：设备端可能发 "true" / true / 1，
     * Studio 可能把值包成 {"value": true, "time": 1750000000000}。
     * 这些形态在开发期一个都验证不了，宽松解析比严格解析更可能一次跑通；
     * 取不到就返回 null，让业务层按"这个字段没上报"处理，
     * 绝不用默认值顶替 —— 拿 false 顶替 alarm_flag 会把真实告警吃掉。
     * ------------------------------------------------------------ */

    /** 取第一个存在且非空的文本值。数字也当字符串收下（规范里 seat_id 就是字符串） */
    private static String text(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode child = node.get(name);
            if (child != null && child.isValueNode() && !child.isNull()) {
                String value = child.asText();
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    /** 拆掉 Studio 那层 {@code {"value": x, "time": t}} 包装 */
    private static JsonNode valueNode(JsonNode scope, String name) {
        if (scope == null) {
            return null;
        }
        JsonNode child = scope.get(name);
        if (child == null || child.isNull()) {
            return null;
        }
        if (child.isObject()) {
            JsonNode value = child.get("value");
            return value == null || value.isNull() ? null : value;
        }
        return child;
    }

    private static Integer asInt(JsonNode scope, String name) {
        JsonNode node = valueNode(scope, name);
        if (node == null) {
            return null;
        }
        if (node.canConvertToInt()) {
            return node.asInt();
        }
        try {
            return Integer.valueOf(node.asText().trim());
        } catch (NumberFormatException e) {
            log.warn("属性 {} 的值不是整数，已忽略: {}", name, node.asText());
            return null;
        }
    }

    private static Long asLong(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode child = valueNode(node, name);
            if (child == null) {
                continue;
            }
            if (child.canConvertToLong()) {
                return child.asLong();
            }
            try {
                return Long.valueOf(child.asText().trim());
            } catch (NumberFormatException ignored) {
                // 平台有时把时间发成 ISO 字符串，TimeUtil.fromText 才管那种，
                // 这里取不到就让上层退回服务器当前时间
            }
        }
        return null;
    }

    private static Boolean asBool(JsonNode scope, String name) {
        JsonNode node = valueNode(scope, name);
        if (node == null) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.asInt() != 0;
        }
        String text = node.asText().trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return Boolean.FALSE;
        }
        log.warn("属性 {} 的值不是布尔，已忽略: {}", name, text);
        return null;
    }

    private static JsonNode firstObject(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode child = node.get(name);
            if (child != null && child.isObject()) {
                return child;
            }
        }
        return null;
    }
}
