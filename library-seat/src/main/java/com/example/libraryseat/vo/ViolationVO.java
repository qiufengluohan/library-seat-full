package com.example.libraryseat.vo;

import com.example.libraryseat.entity.Violation;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 违规记录返回。
 *
 * <p>规范 §11 没定义这个 VO，字段按 violation 表推出来，另外 join 出
 * seat_code 和 student_name —— 管理端违规列表要显示"哪个座位、哪个学生"，
 * 只有 id 的话前端得自己再查两张表。
 *
 * <p>{@code userId} / {@code studentName} 可空：假占座是设备判定的，
 * 那一刻未必知道是谁（violation.user_id 允许 NULL）。
 */
@Data
public class ViolationVO {

    private Long id;

    private Long userId;

    private String studentName;

    private Long seatId;

    private String seatCode;

    /** FAKE_OCCUPY / RESERVATION_TIMEOUT / AWAY_TIMEOUT，前端按字面值映射中文标签。 */
    private String type;

    private String description;

    private LocalDateTime createdAt;

    /**
     * @param studentName user.nickname，可为 null（假占座当时不知道是谁）
     * @param seatCode    seat.seat_code，管理端表格直接显示它而不是 seat_id
     */
    public static ViolationVO of(Violation entity, String studentName, String seatCode) {
        ViolationVO vo = new ViolationVO();
        vo.id = entity.getId();
        vo.userId = entity.getUserId();
        vo.studentName = studentName;
        vo.seatId = entity.getSeatId();
        vo.seatCode = seatCode;
        vo.type = entity.getType();
        vo.description = entity.getDescription();
        vo.createdAt = entity.getCreatedAt();
        return vo;
    }
}
