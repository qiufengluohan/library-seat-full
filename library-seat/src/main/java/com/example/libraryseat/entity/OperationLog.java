package com.example.libraryseat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员操作日志，方案 §36。
 *
 * <p>只有六种操作要记：强制释放、消除告警、修改阈值、测试蜂鸣器、RFID绑定、RFID解绑。
 * {@code operation} 存的就是这几个中文词 —— Web 管理端的违规与统计页按这些字面值
 * 匹配标签颜色，改了要同步改前端。
 */
@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long adminId;

    private String operation;

    /** 操作对象，例如座位号 A1-001 或设备名 SEAT_001。 */
    private String target;

    private String description;

    private LocalDateTime createdAt;
}
