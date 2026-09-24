package com.example.libraryseat.controller;

import com.example.libraryseat.common.Result;
import com.example.libraryseat.security.AuthContext;
import com.example.libraryseat.service.ReservationService;
import com.example.libraryseat.service.SeatService;
import com.example.libraryseat.vo.ReservationVO;
import com.example.libraryseat.vo.SeatDetailVO;
import com.example.libraryseat.vo.SeatVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理员座位视图与两个人工干预动作。编码规范 §14.9 / §14.10。
 *
 * <p>和学生端 {@code SeatController} 分开两个类，是因为返回的东西不一样：
 * 管理员这份带 {@code student_name}（谁占着这个座），学生那份不带。
 * 同一个 URL 前缀下靠 role 决定字段，不如从入口就分成两条路清楚。
 *
 * <p>两个动作接口都<b>没有请求体</b>：管理端 axios 直接 {@code post(url)}，
 * 加了 {@code @RequestBody} 反而会因为空体报 400。
 * 需要谁做的、做了什么，全部进 operation_log，靠 adminId 参数带下去。
 */
@RestController
@RequestMapping("/api/admin/seats")
@RequiredArgsConstructor
public class AdminSeatController {

    private final SeatService seatService;
    private final ReservationService reservationService;

    @GetMapping
    public Result<List<SeatVO>> list() {
        return Result.success(seatService.listSeatsForAdmin());
    }

    @GetMapping("/{id}")
    public Result<SeatDetailVO> detail(@PathVariable Long id) {
        return Result.success(seatService.getDetail(id));
    }

    /**
     * 消除告警：清 {@code seat_shadow.alarm_flag}，把座位状态还原成
     * 当前订单该有的样子，并下发一次显示恢复（§14.10）。
     *
     * <p>座位当前没有告警时返回 409。管理端把 409 当静默业务反馈
     * （{@code error.silent}），弹一句提示后刷新列表，不会当成系统错误。
     */
    @PostMapping("/{id}/clear-alarm")
    public Result<Void> clearAlarm(@PathVariable Long id) {
        seatService.clearAlarm(id, AuthContext.adminId());
        return Result.success();
    }

    /**
     * 强制释放：关掉当前订单 → 座位置 FREE → 写 study_record →
     * 写 operation_log → 下发显示恢复 → 广播（§14.9）。
     *
     * @return 被关掉的订单；<b>座位本来就没有进行中的订单时返回 {@code data: null}</b>，
     *         这不是错误（管理员看到红色告警点进来，人可能已经走了），
     *         operation_log 里仍会留一条"座位当前没有进行中的预约"。
     */
    @PostMapping("/{id}/force-release")
    public Result<ReservationVO> forceRelease(@PathVariable Long id) {
        return Result.success(reservationService.forceRelease(id, AuthContext.adminId()));
    }
}
