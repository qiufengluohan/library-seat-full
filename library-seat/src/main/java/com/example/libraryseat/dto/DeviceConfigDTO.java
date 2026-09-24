package com.example.libraryseat.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 设备参数入参，编码规范 §10.5。
 *
 * <p>0~4095 是 STM32 12 位 ADC 的取值范围，和 OneNET 物模型里
 * {@code adc_threshold} 的 specs 一致。超出这个范围下发过去设备也用不了，
 * 在入口就拦掉比让设备静默忽略要好。
 */
@Data
public class DeviceConfigDTO {

    @NotNull(message = "不能为空")
    @Min(value = 0, message = "不能小于 0")
    @Max(value = 4095, message = "不能大于 4095")
    private Integer adcThreshold;
}
