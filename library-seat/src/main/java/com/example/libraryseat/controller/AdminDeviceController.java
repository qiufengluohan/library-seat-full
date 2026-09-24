package com.example.libraryseat.controller;

import com.example.libraryseat.common.Result;
import com.example.libraryseat.dto.BuzzerDTO;
import com.example.libraryseat.dto.DeviceConfigDTO;
import com.example.libraryseat.security.AuthContext;
import com.example.libraryseat.service.DeviceService;
import com.example.libraryseat.vo.DeviceVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备列表与两个下行动作。编码规范 §13，方案 §42。
 *
 * <h2>路径里的 {deviceId} 是字符串</h2>
 *
 * 是 OneNET 的 device_name（{@code SEAT_001}），<b>不是数据库主键</b>。
 * 管理端 {@code api/device.js} 也是按字符串拼的，并且做了 encodeURIComponent。
 * 写成 Long 会直接 400。
 *
 * <h2>前端永远不碰 OneNET</h2>
 *
 * 方案 §42 / §53：小程序和 Web 都<b>不允许</b>直接拼 OneNET 请求，
 * 凭证只存在于后端环境变量里。这两个接口就是那道翻译层 ——
 * 管理端说"阈值改成 2400"，后端翻译成物模型属性下发
 * {@code {"adc_threshold": 2400}}；说"响 1 秒"，翻译成服务调用
 * {@code buzzer_ctrl(duration_ms=1000)}。
 *
 * <p>下发是 {@code @Async} 且失败只记日志：设备离线时同步等待会把管理员的
 * 请求线程一直挂到读超时，界面上表现为"点了没反应"。所以这两个接口
 * <b>返回 200 只代表指令已交给平台</b>，不代表设备已经执行，
 * 是否真的生效要看随后上报的 pressure_adc / 蜂鸣器状态。
 */
@RestController
@RequestMapping("/api/admin/devices")
@RequiredArgsConstructor
public class AdminDeviceController {

    private final DeviceService deviceService;

    /**
     * 以 seat 表为准列出设备，所以<b>共享读卡器 READER_01 不会出现</b> ——
     * 它不绑定座位，没有阈值可调，也没有蜂鸣器可测，列出来只会让管理员困惑。
     */
    @GetMapping
    public Result<List<DeviceVO>> list() {
        return Result.success(deviceService.listDevices());
    }

    @PostMapping("/{deviceId}/config")
    public Result<Void> config(@PathVariable String deviceId,
                               @Valid @RequestBody DeviceConfigDTO dto) {
        deviceService.updateAdcThreshold(deviceId, dto.getAdcThreshold(), AuthContext.adminId());
        return Result.success();
    }

    @PostMapping("/{deviceId}/buzzer")
    public Result<Void> buzzer(@PathVariable String deviceId,
                               @Valid @RequestBody BuzzerDTO dto) {
        deviceService.testBuzzer(deviceId, dto.getDurationMs(), AuthContext.adminId());
        return Result.success();
    }
}
