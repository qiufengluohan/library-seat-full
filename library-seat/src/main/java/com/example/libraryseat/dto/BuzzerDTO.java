package com.example.libraryseat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 蜂鸣器测试入参，对应 OneNET 服务 {@code buzzer_ctrl} 的入参 duration_ms。
 *
 * <p>上限取物模型里 buzzer_ctrl.duration_ms 的 specs.max（65535）。
 * 管理端页面的输入框限的是 100~10000，这里放宽到物模型上限，
 * 免得两边阈值不一致时后端成为更难排查的那一层。
 */
@Data
public class BuzzerDTO {

    @NotNull(message = "不能为空")
    @Min(value = 0, message = "不能小于 0")
    @Max(value = 65535, message = "不能大于 65535")
    private Integer durationMs;
}
