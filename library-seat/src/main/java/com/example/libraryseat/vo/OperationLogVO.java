package com.example.libraryseat.vo;

import com.example.libraryseat.entity.OperationLog;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员操作日志返回（方案 §36）。
 *
 * <p>{@code operation} 是中文字面值（强制释放 / 消除告警 / 修改阈值 /
 * 测试蜂鸣器 / RFID绑定 / RFID解绑），Web 管理端按这些字符串匹配标签颜色。
 */
@Data
public class OperationLogVO {

    private Long id;

    private Long adminId;

    private String operation;

    private String target;

    private String description;

    private LocalDateTime createdAt;

    public static OperationLogVO of(OperationLog entity) {
        OperationLogVO vo = new OperationLogVO();
        vo.id = entity.getId();
        vo.adminId = entity.getAdminId();
        vo.operation = entity.getOperation();
        vo.target = entity.getTarget();
        vo.description = entity.getDescription();
        vo.createdAt = entity.getCreatedAt();
        return vo;
    }
}
