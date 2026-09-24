package com.example.libraryseat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** RFID 绑定入参，编码规范 §10.4。 */
@Data
public class RfidBindDTO {

    @NotBlank(message = "不能为空")
    private String rfidUid;

    @NotNull(message = "不能为空")
    private Long userId;
}
