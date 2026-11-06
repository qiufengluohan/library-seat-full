package com.example.libraryseat.service.impl;

import com.example.libraryseat.config.OneNetProperties;
import com.example.libraryseat.dto.OneNetDataDTO;
import com.example.libraryseat.service.RfidService;
import com.example.libraryseat.service.SeatService;
import com.example.libraryseat.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * OneNET 上行报文归一化测试。
 *
 * <h2>为什么这个类最值得写测试</h2>
 * <p>
 * {@code normalize} 是整条链路里<b>唯一在开发期无法验证</b>的环节：
 * 编码规范 §15 写的是简洁格式（Postman 联调用），OneNET Studio 真机推来的
 * 是另一种信封，属性值还可能被包成 {@code {"value": x, "time": t}}。
 * 拿不到真机就只能靠这些用例把"所有可能的形态"钉住，
 * 真机联调时若发现新形态，先在这里加一个用例再改实现。
 *
 * <p>另一件被钉住的事：<b>取不到的字段必须是 null，不能是默认值。</b>
 * 把缺失的 {@code alarm_flag} 当成 false，等于把真实告警吃掉 —— 那种 bug
 * 表现为"设备明明报了假占座，管理端却没红"，从日志里完全看不出来。
 */
class OneNetServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SeatService seatService;
    private RfidService rfidService;
    private OneNetServiceImpl service;

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() {
        seatService = mock(SeatService.class);
        rfidService = mock(RfidService.class);
        service = new OneNetServiceImpl(seatService, rfidService, new OneNetProperties(), new JsonUtil(MAPPER));
    }

    /* ------------------------------------------------------------
     * normalize：各种报文形态
     * ------------------------------------------------------------ */

    @Test
    @DisplayName("规范 §15.1 的平铺属性上报")
    void normalizesFlatPropertyReport() {
        OneNetDataDTO dto = service.normalize(json("""
                {"device_id":"SEAT_001","seat_id":"1","pressure_adc":2500,
                 "pir_state":true,"alarm_flag":false,"timestamp":1750000000}
                """));

        assertEquals("SEAT_001", dto.getDeviceId());
        assertEquals("1", dto.getSeatId());
        assertEquals(2500, dto.getPressureAdc());
        assertEquals(Boolean.TRUE, dto.getPirState());
        assertEquals(Boolean.FALSE, dto.getAlarmFlag());
        assertEquals(1750000000L, dto.getTimestamp());
        assertNull(dto.getEvent());
        assertNull(dto.getRfidUid());
        assertNull(dto.getOnline());
    }

    @Test
    @DisplayName("Studio 信封：属性套在 params 里，值再包一层 {value,time}")
    void normalizesStudioEnvelopeWithWrappedValues() {
        OneNetDataDTO dto = service.normalize(json("""
                {"device_name":"SEAT_001","time":1750000001000,
                 "params":{"pressure_adc":{"value":1800,"time":1750000001000},
                           "pir_state":{"value":false}}}
                """));

        assertEquals("SEAT_001", dto.getDeviceId());
        assertEquals(1800, dto.getPressureAdc());
        assertEquals(Boolean.FALSE, dto.getPirState());
        // 顶层 time 优先于 params 里的 time
        assertEquals(1750000001000L, dto.getTimestamp());
        assertNull(dto.getAlarmFlag());
    }

    @Test
    @DisplayName("设备端把布尔和数字发成字符串也要认（固件好拼）")
    void normalizesStringifiedValues() {
        OneNetDataDTO dto = service.normalize(json("""
                {"device_id":"SEAT_001","pressure_adc":"2048","pir_state":"1","alarm_flag":"true"}
                """));

        assertEquals(2048, dto.getPressureAdc());
        assertEquals(Boolean.TRUE, dto.getPirState());
        assertEquals(Boolean.TRUE, dto.getAlarmFlag());
    }

    @Test
    @DisplayName("数字 0/1 当布尔：0 是 false，不是'没有这个字段'")
    void normalizesNumericBooleans() {
        OneNetDataDTO dto = service.normalize(json("""
                {"device_id":"SEAT_001","pir_state":1,"alarm_flag":0}
                """));

        assertEquals(Boolean.TRUE, dto.getPirState());
        assertEquals(Boolean.FALSE, dto.getAlarmFlag());
    }

    @Test
    @DisplayName("§15.4 心跳：只有 online，其余一律留空")
    void normalizesHeartbeat() {
        OneNetDataDTO dto = service.normalize(json("""
                {"device_id":"SEAT_001","online":true,"timestamp":1750000004}
                """));

        assertEquals(Boolean.TRUE, dto.getOnline());
        assertNull(dto.getPressureAdc());
        assertNull(dto.getPirState());
        assertNull(dto.getAlarmFlag());
    }

    @Test
    @DisplayName("§15.2 刷卡事件：READER_01 + rfid_uid")
    void normalizesRfidScan() {
        OneNetDataDTO dto = service.normalize(json("""
                {"device_id":"READER_01","event":"rfid_scan",
                 "rfid_uid":"A1B2C3D4","timestamp":1750000003}
                """));

        assertEquals("READER_01", dto.getDeviceId());
        assertEquals("rfid_scan", dto.getEvent());
        assertEquals("A1B2C3D4", dto.getRfidUid());
    }

    @Test
    @DisplayName("§15.3 假占座事件不带 alarm_flag 时补成 true")
    void fakeOccupyEventImpliesAlarm() {
        OneNetDataDTO dto = service.normalize(json("""
                {"device_id":"SEAT_001","event":"fake_occupy",
                 "pressure_adc":3000,"pir_state":false,"timestamp":1750000002}
                """));

        assertEquals("fake_occupy", dto.getEvent());
        assertEquals(Boolean.FALSE, dto.getPirState());
        // 事件本身就代表"设备判定假占座"，不补的话告警联动走不到 SeatService 的翻转判断
        assertEquals(Boolean.TRUE, dto.getAlarmFlag());
    }

    @Test
    @DisplayName("fake_occupy 显式带了 alarm_flag 就尊重报文，不覆盖")
    void explicitAlarmFlagWins() {
        OneNetDataDTO dto = service.normalize(json("""
                {"device_id":"SEAT_001","event":"fake_occupy","alarm_flag":false}
                """));

        assertEquals(Boolean.FALSE, dto.getAlarmFlag());
    }

    @Test
    @DisplayName("identifier 是属性名时不能当事件，否则普通上报会走进签到分支")
    void identifierIsNotAnEventUnlessKnown() {
        OneNetDataDTO dto = service.normalize(json("""
                {"device_name":"SEAT_001","identifier":"pressure_adc",
                 "params":{"pressure_adc":100}}
                """));

        assertNull(dto.getEvent());
        assertEquals(100, dto.getPressureAdc());

        assertEquals("fake_occupy", service.normalize(json("""
                {"device_name":"SEAT_001","identifier":"fake_occupy"}
                """)).getEvent());
    }

    @Test
    @DisplayName("取不到的字段是 null，绝不填默认值")
    void missingFieldsStayNull() {
        OneNetDataDTO empty = service.normalize(json("{}"));
        assertNull(empty.getDeviceId());
        assertNull(empty.getSeatId());
        assertNull(empty.getPressureAdc());
        assertNull(empty.getPirState());
        assertNull(empty.getAlarmFlag());
        assertNull(empty.getOnline());
        assertNull(empty.getTimestamp());
        assertNull(empty.getEvent());
        assertNull(empty.getRfidUid());
    }

    @Test
    @DisplayName("报文为空 / 不是对象时返回空 DTO，不抛异常也不返回 null")
    void toleratesBrokenPayload() {
        assertDoesNotThrow(() -> service.normalize(null));
        OneNetDataDTO fromNull = service.normalize(null);
        assertNull(fromNull.getDeviceId());

        OneNetDataDTO fromArray = service.normalize(json("[1,2,3]"));
        assertNull(fromArray.getDeviceId());
    }

    @Test
    @DisplayName("值不是布尔也不是 0/1 时忽略该字段，不猜")
    void ignoresUnparsableValues() {
        OneNetDataDTO dto = service.normalize(json("""
                {"device_id":"SEAT_001","pir_state":"maybe","pressure_adc":"很多"}
                """));

        assertNull(dto.getPirState());
        assertNull(dto.getPressureAdc());
        assertEquals("SEAT_001", dto.getDeviceId());
    }

    /* ------------------------------------------------------------
     * normalizeAll：双座位拆包（docs/05 §2）
     * ------------------------------------------------------------ */

    @Test
    @DisplayName("一包两座位：按 _1/_2 后缀拆成两条 DTO")
    void splitsTwoSeatPropertyReport() {
        OneNetDataDTO[] dtos = service.normalizeAll(json("""
                {"device_name":"SEAT_001","params":{
                  "pressure_adc_1":{"value":1200},
                  "pressure_adc_2":{"value":32},
                  "pir_state_1":{"value":true},
                  "pir_state_2":{"value":false},
                  "alarm_flag_1":{"value":false},
                  "alarm_flag_2":{"value":true}}}
                """)).toArray(new OneNetDataDTO[0]);

        assertEquals(2, dtos.length);
        assertEquals(1, dtos[0].getSeatNo());
        assertEquals(1200, dtos[0].getPressureAdc());
        assertEquals(Boolean.TRUE, dtos[0].getPirState());
        assertEquals(Boolean.FALSE, dtos[0].getAlarmFlag());

        assertEquals(2, dtos[1].getSeatNo());
        assertEquals(32, dtos[1].getPressureAdc());
        assertEquals(Boolean.FALSE, dtos[1].getPirState());
        assertEquals(Boolean.TRUE, dtos[1].getAlarmFlag());
    }

    @Test
    @DisplayName("老固件的无后缀字段归到座位 1，单座位报文继续可用")
    void legacyFieldsGoToSeatOne() {
        OneNetDataDTO dto = service.normalize(json("""
                {"device_id":"SEAT_001","pressure_adc":2500,"pir_state":true}
                """));

        assertEquals(1, dto.getSeatNo());
        assertEquals(2500, dto.getPressureAdc());
    }

    @Test
    @DisplayName("fake_occupy 事件按 seat_no 只作用于那一个座位")
    void fakeOccupyEventCarriesSeatNo() {
        OneNetDataDTO dto = service.normalize(json("""
                {"device_id":"SEAT_001","event":"fake_occupy","seat_no":2,
                 "pressure_adc":3000,"pir_state":false}
                """));

        assertEquals("fake_occupy", dto.getEvent());
        assertEquals(2, dto.getSeatNo());
        assertEquals(Boolean.TRUE, dto.getAlarmFlag());
    }

    @Test
    @DisplayName("心跳没有座位维度：seatNo 为 null，由业务层按整机处理")
    void heartbeatHasNoSeatNo() {
        OneNetDataDTO dto = service.normalize(json("""
                {"device_id":"SEAT_001","online":true}
                """));

        assertNull(dto.getSeatNo());
        assertEquals(Boolean.TRUE, dto.getOnline());
    }

    @Test
    @DisplayName("handleDeviceData 对两座位报文调用两次 applyDeviceReport")
    void twoSeatPacketAppliesTwice() {
        service.handleDeviceData(json("""
                {"device_name":"SEAT_001","params":{
                  "pressure_adc_1":{"value":1200},
                  "pressure_adc_2":{"value":32}}}
                """));

        verify(seatService, org.mockito.Mockito.times(2))
                .applyDeviceReport(any(OneNetDataDTO.class));
    }

    /* ------------------------------------------------------------
     * handleDeviceData：分流
     * ------------------------------------------------------------ */

    @Test
    @DisplayName("普通属性上报只写影子表，不碰签到")
    void propertyReportGoesToSeatServiceOnly() {
        service.handleDeviceData(json("""
                {"device_id":"SEAT_001","pressure_adc":2500,"pir_state":true}
                """));

        verify(seatService).applyDeviceReport(any(OneNetDataDTO.class));
        verify(rfidService, never()).signInByUid(anyString());
    }

    @Test
    @DisplayName("纯刷卡报文不走 applyDeviceReport：READER_01 没有座位，只会多一条 warn")
    void pureRfidScanSkipsSeatService() {
        when(rfidService.signInByUid("A1B2C3D4")).thenReturn(true);

        boolean signed = service.handleRfidEvent(json("""
                {"device_id":"READER_01","event":"rfid_scan","rfid_uid":"A1B2C3D4"}
                """));

        assertTrue(signed);
        verify(rfidService).signInByUid("A1B2C3D4");
        verify(seatService, never()).applyDeviceReport(any());
    }

    @Test
    @DisplayName("device-data 端点收到刷卡也要签到，同时因为带了传感器数据仍然写影子表")
    void rfidScanWithSensorDataDoesBoth() {
        service.handleDeviceData(json("""
                {"device_id":"SEAT_001","event":"rfid_scan","rfid_uid":"A1B2C3D4",
                 "pressure_adc":2500}
                """));

        verify(rfidService).signInByUid("A1B2C3D4");
        verify(seatService).applyDeviceReport(any(OneNetDataDTO.class));
    }

    @Test
    @DisplayName("rfid-event 里没有卡号时返回 false，不抛异常")
    void rfidEventWithoutUidIsIgnored() {
        assertEquals(false, service.handleRfidEvent(json("""
                {"device_id":"READER_01","event":"rfid_scan"}
                """)));
        verify(rfidService, never()).signInByUid(anyString());
    }

    /* ------------------------------------------------------------
     * 2026-10-29 补：报文里没有 device 字段的两种真实形态
     * ------------------------------------------------------------ */

    @Test
    @DisplayName("只给 MQTT topic 时从 topic 里取设备名（OneJSON 属性报文本身没有设备名）")
    void deviceNameFromTopic() {
        OneNetDataDTO dto = service.normalize(json("""
                {"topic":"$sys/Wh08f3Q71p/SEAT_001/thing/property/post",
                 "params":{"pressure_adc_1":{"value":1200}}}
                """));

        assertEquals("SEAT_001", dto.getDeviceId());
        assertEquals(1200, dto.getPressureAdc());
        assertEquals(1, dto.getSeatNo());
    }

    @Test
    @DisplayName("信封里的 msg 直接是对象（不是字符串）也要能解开")
    void unwrapEnvelopeWithObjectMsg() {
        OneNetDataDTO dto = service.normalize(json("""
                {"msg":{"device_name":"SEAT_001","params":{"pressure_adc_2":{"value":32}}}}
                """));

        assertEquals("SEAT_001", dto.getDeviceId());
        assertEquals(32, dto.getPressureAdc());
        assertEquals(2, dto.getSeatNo());
    }

    @Test
    @DisplayName("彻底认不出设备名时不抛异常，也不往下游传（只记一条 warn + 原始报文）")
    void unknownDeviceDoesNotThrow() {
        assertDoesNotThrow(() -> service.handleDeviceData(json("{}")));
        verify(seatService, never()).applyDeviceReport(any());
    }

    @Test
    @DisplayName("OneNET 把业务报文 Base64 编码放进 msg（2026-10-29 真机形态）")
    void unwrapsBase64Msg() {
        String oneJson = """
                {"id":"120","version":"1.0","params":{
                  "pressure_adc_1":{"value":6},
                  "pressure_adc_2":{"value":6},
                  "pir_state_1":{"value":false},
                  "pir_state_2":{"value":false},
                  "alarm_flag_1":{"value":false},
                  "alarm_flag_2":{"value":false}}}
                """;
        String base64 = java.util.Base64.getEncoder()
                .encodeToString(oneJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        OneNetDataDTO[] dtos = service.normalizeAll(
                json("{\"msg\":\"" + base64 + "\",\"nonce\":123}")).toArray(new OneNetDataDTO[0]);

        assertEquals(2, dtos.length);
        assertEquals(1, dtos[0].getSeatNo());
        assertEquals(6, dtos[0].getPressureAdc());
        assertEquals(2, dtos[1].getSeatNo());
        assertEquals(6, dtos[1].getPressureAdc());
    }

    @Test
    @DisplayName("整个 body 就是 Base64 字符串时也能解开")
    void unwrapsBase64Body() {
        String oneJson = """
                {"device_name":"SEAT_001","params":{"pressure_adc_1":{"value":900}}}
                """;
        String base64 = java.util.Base64.getEncoder()
                .encodeToString(oneJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        OneNetDataDTO dto = service.normalize(MAPPER.getNodeFactory().textNode(base64));

        assertEquals("SEAT_001", dto.getDeviceId());
        assertEquals(900, dto.getPressureAdc());
    }

    @Test
    @DisplayName("设备名在信封外层、msg 是 Base64（推送 URL 上的 ?device= 也走这条路）")
    void deviceNameOnEnvelopeWithBase64Msg() {
        String oneJson = """
                {"id":"120","version":"1.0","params":{"pressure_adc_1":{"value":6}}}
                """;
        String base64 = java.util.Base64.getEncoder()
                .encodeToString(oneJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        OneNetDataDTO dto = service.normalize(json(
                "{\"device_name\":\"SEAT_001\",\"msg\":\"" + base64 + "\",\"time\":1750000000000}"));

        assertEquals("SEAT_001", dto.getDeviceId());
        assertEquals(6, dto.getPressureAdc());
        assertEquals(1750000000000L, dto.getTimestamp());
    }
}
