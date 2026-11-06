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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OneNetServiceImpl implements OneNetService {

    /**
     * Studio 推送可能把属性套在这些字段里，规范 §15 的简洁格式则是平铺在顶层
     */
    private static final String[] PROPERTY_CONTAINERS = {"params", "data", "body", "properties"};

    /**
     * 一台设备最多带几个座位（docs/05 是 2）
     */
    private static final int MAX_SEAT_NO = 2;

    /**
     * 没有 {@code _N} 后缀的老字段归属的座位号
     */
    private static final int LEGACY_SEAT_NO = 1;

    private final SeatService seatService;
    private final RfidService rfidService;
    private final OneNetProperties oneNetProperties;
    private final JsonUtil jsonUtil;

    /**
     * Base64 提示只打一次。
     *
     * @note 设备每推一条报文都会走一次 Base64 解码，每次都 INFO 会把日志刷满；
     * 这里用 CAS 保证整个进程只提示一次（不是 static：每个 Bean 实例一份就够）。
     */
    private final java.util.concurrent.atomic.AtomicBoolean base64NoticeLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 复制公共字段，只换座位号。
     */
    private static OneNetDataDTO copyOf(OneNetDataDTO base, Integer seatNo) {
        OneNetDataDTO dto = new OneNetDataDTO();
        dto.setDeviceId(base.getDeviceId());
        dto.setSeatId(base.getSeatId());
        dto.setSeatNo(seatNo);
        dto.setOnline(base.getOnline());
        dto.setTimestamp(base.getTimestamp());
        dto.setEvent(base.getEvent());
        dto.setRfidUid(base.getRfidUid());
        return dto;
    }

    private static String suffixed(String prop, int seatNo) {
        return prop + SEAT_SUFFIX_SEPARATOR + seatNo;
    }

    /**
     * 先按 {@code prop_N} 取，取不到再退回无后缀的老字段。
     */
    private static Integer seatValue(JsonNode scope, String prop, int seatNo) {
        Integer value = asInt(scope, suffixed(prop, seatNo));
        return value != null ? value : asInt(scope, prop);
    }

    /* ------------------------------------------------------------
     * 报文归一化
     * ------------------------------------------------------------ */

    private static Boolean seatBool(JsonNode scope, String prop, int seatNo) {
        Boolean value = asBool(scope, suffixed(prop, seatNo));
        return value != null ? value : asBool(scope, prop);
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

    /**
     * 从 MQTT topic 里取设备名：{@code $sys/{productId}/{deviceName}/thing/...}。
     *
     * <p>有的推送通道不给 {@code device_name} 字段，只在 topic 里带设备名，
     * 而 OneJSON 的**属性报文里本来就没有设备名**（设备名只在 topic 上）。
     * 所以能读 topic 就读，读不到再靠推送 URL 上的 {@code ?device=} 兜底。
     */
    private static String deviceFromTopic(String topic) {
        if (topic == null || !topic.startsWith("$sys/")) {
            return null;
        }
        String[] parts = topic.split("/");
        if (parts.length >= 3 && parts[2] != null && !parts[2].isBlank()) {
            return parts[2].trim();
        }
        return null;
    }

    /**
     * 日志里截断超长报文：够看清结构，又不至于把日志刷爆
     */
    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "null";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...(共 " + text.length() + " 字符)";
    }

    private static boolean isRfidScan(OneNetDataDTO dto) {
        return EVENT_RFID_SCAN.equalsIgnoreCase(dto.getEvent())
                || (dto.getRfidUid() != null && !dto.getRfidUid().isBlank());
    }

    private static boolean hasSensorData(OneNetDataDTO dto) {
        return dto.getPressureAdc() != null || dto.getPirState() != null
                || dto.getAlarmFlag() != null || dto.getOnline() != null;
    }

    /**
     * 取第一个存在且非空的文本值。数字也当字符串收下（规范里 seat_id 就是字符串）
     */
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

    /**
     * 拆掉 Studio 那层 {@code {"value": x, "time": t}} 包装
     */
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

    /* ------------------------------------------------------------
     * JsonNode 取值。
     *
     * 之所以写得这么"什么都认"：设备端可能发 "true" / true / 1，
     * Studio 可能把值包成 {"value": true, "time": 1750000000000}。
     * 这些形态在开发期一个都验证不了，宽松解析比严格解析更可能一次跑通；
     * 取不到就返回 null，让业务层按"这个字段没上报"处理，
     * 绝不用默认值顶替 —— 拿 false 顶替 alarm_flag 会把真实告警吃掉。
     * ------------------------------------------------------------ */

    @Override
    public void handleDeviceData(JsonNode payload) {
        // 双座位：一包可能拆出两条 DTO，各走一遍签到判断与影子表落库
        for (OneNetDataDTO dto : normalizeAll(payload)) {
            if (dto.getDeviceId() == null || dto.getDeviceId().isBlank()) {
                // normalizeAll 已经打过一条「找不到设备名 + 原始报文」的 WARN；
                // 再让 applyDeviceReport 打一遍，会让人误以为是两个不同的问题。
                continue;
            }
            boolean rfidScan = isRfidScan(dto);
            if (rfidScan) {
                boolean signedIn = rfidService.signInByUid(dto.getRfidUid());
                log.info("上行刷卡事件: deviceId={}, seatNo={}, rfidUid={}, 签到成功={}",
                        dto.getDeviceId(), dto.getSeatNo(), dto.getRfidUid(), signedIn);
            }
            // 纯刷卡报文来自共享读卡器 READER_01，它不对应任何座位，
            // 交给 applyDeviceReport 只会多打一条没意义的 warn
            if (!rfidScan || hasSensorData(dto)) {
                seatService.applyDeviceReport(dto);
            }
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

    @Override
    public OneNetDataDTO normalize(JsonNode payload) {
        // 单座位调用方（handleRfidEvent、老测试）只关心第一条。
        // 双座位请直接用 normalizeAll。
        List<OneNetDataDTO> all = normalizeAll(payload);
        return all.isEmpty() ? new OneNetDataDTO() : all.get(0);
    }

    @Override
    public List<OneNetDataDTO> normalizeAll(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            log.warn("OneNET 上行报文为空，已忽略");
            return List.of();
        }
        if (log.isDebugEnabled()) {
            // 真机联调时第一件事就是打开这行日志看平台到底发了什么，
            // 然后照着改本方法。别的地方都不用动。
            log.debug("OneNET 上行原始报文: {}", payload);
        }

        // 允许 body 本身就是字符串（JSON 字符串 / Base64），先解开再判断是不是对象
        JsonNode root = unwrapEnvelope(payload);
        if (root == null || !root.isObject()) {
            log.warn("OneNET 上行报文不是 JSON 对象，已忽略。原始报文: {}",
                    abbreviate(String.valueOf(payload), 300));
            return List.of();
        }

        JsonNode scope = firstObject(root, PROPERTY_CONTAINERS);
        if (scope == null) {
            scope = root;
        }
        // OneNET 的 data 里面还套了一层 params，属性值在那里面
        JsonNode nestedParams = firstObject(scope, "params");
        if (nestedParams != null) {
            scope = nestedParams;
        }

        /* 🔴 OneJSON 事件上报的结构和属性上报**不一样**（官方《设备属性/事件》§4）：
         *     属性：{"params":{"pressure_adc":{"value":3972,"time":...}}}   value 是标量
         *     事件：{"params":{"fake_occupy":{"value":{...输出参数...}}}}  value 是对象
         *   所以这里用"params 只有一项、且它的 value 是对象"来识别事件报文，
         *   命中就把 scope 下潜到输出参数对象里，后面的 pressure_adc / pir_state
         *   才能按老逻辑读到。属性报文永远不会命中（value 是标量）。
         *   2026-10-19：设备端事件 topic 改成 .../thing/event/post 后，标识符只出现在
         *   params 的键上，所以这里顺便把它记成 eventKey，供下面 dto.setEvent 兜底。 */
        String eventKey = null;
        if (scope != null && scope.isObject() && scope.size() == 1) {
            java.util.Iterator<String> names = scope.fieldNames();
            String key = names.hasNext() ? names.next() : null;
            JsonNode wrapper = key == null ? null : scope.get(key);
            if (wrapper != null && wrapper.isObject()
                    && wrapper.get("value") != null && wrapper.get("value").isObject()) {
                eventKey = key;
                scope = wrapper.get("value");
            }
        }

        String deviceId = text(root, "device_name", "device_id", "devName", "deviceId", "deviceName", "dev");
        if (deviceId == null) {
            deviceId = text(root.get("device"), "device_name", "device_id", "name");
        }
        // 有些推送只给 MQTT topic（$sys/{pid}/{dev}/thing/...），设备名在里面
        if (deviceId == null) {
            deviceId = deviceFromTopic(text(root, "topic", "topicName"));
        }
        if (deviceId == null) {
            // §15.3 的 fake_occupy 事件把 device_id 放在输出参数里，不在信封上
            deviceId = text(scope, "device_id", "device_name");
        }
        if (deviceId == null) {
            deviceId = deviceFromTopic(text(scope, "topic", "topicName"));
        }
        if (deviceId == null && root != payload) {
            /* 信封外层也可能是设备名的来源：
               · 平台推送时可以把 deviceName 放在信封上，业务报文（msg）里没有；
               · 推送 URL 上的 ?device=SEAT_001 也是被塞进这一层的（OneNetController）。
               所以内层取不到时必须回头再看一眼外层，否则 ?device= 白加。 */
            deviceId = text(payload, "device_name", "device_id", "devName", "deviceId", "deviceName", "dev");
        }
        if (deviceId == null) {
            // 🔴 认不出设备名时**必须把原始报文打出来**：
            //    这是整条链路唯一无法在开发期验证的地方，只回一句"缺少 device_id"
            //    等于把唯一的线索丢了（2026-10-29 就踩过：日志只有全 null 的 DTO，
            //    完全看不出平台到底推了什么）。截断 500 字符，够看清结构又不刷屏。
            log.warn("上行报文里找不到设备名（device_name / device_id / topic 都没有），这条只能忽略。"
                    + "原始报文: {}", abbreviate(payload.toString(), 500));
        }

        String seatId = text(root, "seat_id", "seatId");
        if (seatId == null) {
            seatId = text(scope, "seat_id", "seatId");
        }

        // online 既可能在信封顶层，也可能在 data/params 里（§15.4 心跳）
        Boolean online = asBool(root, "online");
        if (online == null) {
            online = asBool(scope, "online");
        }

        Long timestamp = asLong(root, "timestamp", "time", "ts");
        if (timestamp == null) {
            timestamp = asLong(scope, "timestamp", "time", "ts");
        }
        if (timestamp == null && root != payload) {
            // 信封上的 time 是平台推送时刻，比设备端填的 0 更有意义（设备没有 RTC）
            timestamp = asLong(payload, "timestamp", "time", "ts");
        }

        String rfidUid = text(scope, PARAM_RFID_UID, "rfidUid", "uid");
        if (rfidUid == null) {
            rfidUid = text(root, PARAM_RFID_UID, "rfidUid", "uid");
        }

        String event = eventName(root, scope);
        if (event == null) {
            event = eventKey;   // 事件报文的标识符只出现在 params 的键上
        }

        OneNetDataDTO base = new OneNetDataDTO();
        base.setDeviceId(deviceId);
        base.setSeatId(seatId);
        base.setOnline(online);
        base.setTimestamp(timestamp);
        base.setEvent(event);
        base.setRfidUid(rfidUid);

        /* 假占座事件只描述**一个**座位：输出参数里带 seat_no（docs/05 §2.2）。
         * 它的属性名没有 _N 后缀，所以不能走下面的按座位扫描，单独处理。 */
        if (EVENT_FAKE_OCCUPY.equalsIgnoreCase(event)) {
            Integer seatNo = asInt(scope, PARAM_SEAT_NO);
            if (seatNo == null) {
                seatNo = asInt(scope, "seatNo");
            }
            if (seatNo == null) {
                seatNo = asInt(root, PARAM_SEAT_NO);
            }
            if (seatNo == null) {
                log.warn("fake_occupy 事件缺少 seat_no，按座位 {} 处理（deviceId={}）",
                        LEGACY_SEAT_NO, deviceId);
                seatNo = LEGACY_SEAT_NO;
            }
            OneNetDataDTO dto = copyOf(base, seatNo);
            dto.setPressureAdc(seatValue(scope, "pressure_adc", seatNo));
            dto.setPirState(seatBool(scope, "pir_state", seatNo));
            Boolean alarm = seatBool(scope, "alarm_flag", seatNo);
            // 事件本身就代表"设备判定为假占座"，报文里不一定再带一遍 alarm_flag。
            // 补上它，告警联动才走得到 SeatService 那个统一的翻转判断里。
            dto.setAlarmFlag(alarm != null ? alarm : Boolean.TRUE);
            return List.of(dto);
        }

        /* 属性上报：按 _1/_2 后缀拆到各座位。
         * 老固件没有后缀，归到座位 1，保证单座位报文继续可用。 */
        Map<Integer, OneNetDataDTO> bySeat = new LinkedHashMap<>();
        for (int seatNo = 1; seatNo <= MAX_SEAT_NO; seatNo++) {
            Integer pressure = asInt(scope, suffixed("pressure_adc", seatNo));
            Boolean pir = asBool(scope, suffixed("pir_state", seatNo));
            Boolean alarm = asBool(scope, suffixed("alarm_flag", seatNo));
            if (pressure != null || pir != null || alarm != null) {
                OneNetDataDTO dto = copyOf(base, seatNo);
                dto.setPressureAdc(pressure);
                dto.setPirState(pir);
                dto.setAlarmFlag(alarm);
                bySeat.put(seatNo, dto);
            }
        }

        Integer legacyPressure = asInt(scope, "pressure_adc");
        Boolean legacyPir = asBool(scope, "pir_state");
        Boolean legacyAlarm = asBool(scope, "alarm_flag");
        if (legacyPressure != null || legacyPir != null || legacyAlarm != null) {
            OneNetDataDTO dto = bySeat.computeIfAbsent(LEGACY_SEAT_NO,
                    n -> copyOf(base, LEGACY_SEAT_NO));
            // 同一条报文里新旧字段并存时，带后缀的优先，这里只补空缺
            if (dto.getPressureAdc() == null) {
                dto.setPressureAdc(legacyPressure);
            }
            if (dto.getPirState() == null) {
                dto.setPirState(legacyPir);
            }
            if (dto.getAlarmFlag() == null) {
                dto.setAlarmFlag(legacyAlarm);
            }
        }

        if (!bySeat.isEmpty()) {
            return List.copyOf(bySeat.values());
        }

        // 心跳 / 纯刷卡 / 设备状态通知：没有座位维度，交给业务层按整机处理
        return List.of(copyOf(base, null));
    }

    /**
     * OneNET 全局推送把业务报文当成【字符串】塞在 msg 字段里，外面套着
     * signature / nonce / time / id。不解开这一层，deviceName 和 data.params 都够不到。
     *
     * <p>编码规范 §15 的简洁格式没有 msg 字段，那种原样返回，两种都吃得下。
     */
    private JsonNode unwrapEnvelope(JsonNode payload) {
        // 形态 0：整个 body 就是一个字符串（可能是 JSON 文本，也可能是 Base64）
        if (payload.isTextual()) {
            JsonNode inner = parseJsonOrBase64(payload.asText());
            return inner != null ? inner : payload;
        }
        JsonNode msg = payload.get("msg");
        // 有些推送通道直接把 msg 给成对象，不是"字符串里的 JSON"，这种不用再解析一次
        if (msg != null && msg.isObject()) {
            return msg;
        }
        // 🔴 2026-10-29 实测：OneNET Studio 的推送会把业务报文 **Base64 编码**后放进 msg。
        //    只按纯 JSON 解析的话 msg 解不出来，整条 DTO 全是 null，座位永远不更新。
        if (msg != null && msg.isTextual()) {
            JsonNode inner = parseJsonOrBase64(msg.asText());
            if (inner != null) {
                return inner;
            }
            log.warn("推送信封里的 msg 既不是 JSON 也不是 Base64 里的 JSON，按原报文处理: {}",
                    abbreviate(msg.asText(), 200));
            return payload;
        }
        // 另一些通道把业务报文放在 data（字符串）里，和 msg 一个套路
        JsonNode data = payload.get("data");
        if (data != null && data.isTextual()) {
            JsonNode inner = parseJsonOrBase64(data.asText());
            if (inner != null) {
                return inner;
            }
        }
        return payload;
    }

    /**
     * 把一段文本解析成 JSON 对象：先按 JSON 试，不是 JSON 再按 Base64 解一次。
     *
     * @return 解析出的对象；两种都不是时返回 null（调用方自己决定怎么兜底）
     * @note 顺序不能反：正常 JSON 里不可能出现合法的 Base64 解码结果碰巧也是 JSON，
     * 但先解 Base64 会把普通 JSON 误伤（JSON 文本本身就是合法 Base64 字符集）。
     */
    private JsonNode parseJsonOrBase64(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        JsonNode direct = jsonUtil.readTree(text);
        if (direct != null && direct.isObject()) {
            return direct;
        }
        String trimmed = text.trim();
        try {
            String decoded = new String(Base64.getDecoder().decode(trimmed), StandardCharsets.UTF_8);
            JsonNode fromBase64 = jsonUtil.readTree(decoded);
            if (fromBase64 != null && fromBase64.isObject()) {
                // ⚠️ 设备每推一条都会走到这里，打 INFO 会把日志刷满；
                //    只在**第一次**提示一次，之后要排查就把这个包调成 debug。
                log.debug("推送报文是 Base64 编码，已解码成 OneJSON（{} 字符）", decoded.length());
                if (base64NoticeLogged.compareAndSet(false, true)) {
                    log.info("检测到 OneNET 推送的业务报文是 Base64 编码，已自动解码（{} 字符）；"
                            + "后续同类报文不再重复提示", decoded.length());
                }
                return fromBase64;
            }
        } catch (IllegalArgumentException ignored) {
            // 不是 Base64：交给调用方按原报文处理
        }
        return null;
    }
}
