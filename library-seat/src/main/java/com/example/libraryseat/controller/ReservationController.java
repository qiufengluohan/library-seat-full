package com.example.libraryseat.controller;

import com.example.libraryseat.common.Result;
import com.example.libraryseat.dto.ReservationCreateDTO;
import com.example.libraryseat.security.AuthContext;
import com.example.libraryseat.service.ReservationService;
import com.example.libraryseat.vo.ReservationVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学生端预约全流程。编码规范 §13 / §14.3 ~ §14.8。
 *
 * <h2>没有 /sign 接口</h2>
 *
 * §14.8 明确签到<b>只能由 RFID 触发</b>：读卡器上报 rfid_uid →
 * {@code OneNetController} → {@code RfidService#signInByUid}。
 * 所以这里不提供任何"点一下按钮就算签到"的端点 ——
 * 提供了就等于允许人在宿舍里远程占座，整个假占座检测也就没有意义了。
 * 小程序签到页显示的倒计时和"请刷卡"提示都建立在这个前提上。
 *
 * <h2>userId 从 token 取</h2>
 *
 * 五个动作接口都只收 reservationId，不收 userId：Service 内部会用
 * {@code requireOwn} 校验这条订单确实属于当前登录人，不属于就 403。
 * 少一个可被前端篡改的入参，就少一个越权面。
 *
 * <h2>动作接口必须返回更新后的订单</h2>
 *
 * 小程序 {@code reservationAction()} 把响应直接过 {@code normReservation} 刷页面，
 * 返回 null 会让它把刚取消的订单继续显示成"使用中"。
 */
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    /**
     * 当前进行中的预约。<b>没有时返回 {@code data: null}，不是 404</b> ——
     * 小程序首页每次进入都调它，404 会被 {@code request.js} 当成错误弹提示，
     * 一个没预约的学生每次打开小程序都会看到一次报错。
     *
     * <p>写在 {@code /{id}} 之前只是为了阅读顺序；Spring 匹配时字面量路径
     * 天然优先于路径变量，所以 "current" 不会被当成 id 去转 Long。
     */
    @GetMapping("/current")
    public Result<ReservationVO> current() {
        return Result.success(reservationService.getCurrent(AuthContext.userId()));
    }

    @PostMapping
    public Result<ReservationVO> create(@Valid @RequestBody ReservationCreateDTO dto) {
        return Result.success(reservationService.create(AuthContext.userId(), dto));
    }

    /**
     * 我的预约列表。
     *
     * @param status ALL / RESERVED / USING / AWAY / FINISHED，为空等同于 ALL。
     *               FINISHED 是 COMPLETED + CANCELLED + TIMEOUT 的归并，
     *               由 {@code ReservationStatus#parseFilter} 在服务端展开 ——
     *               小程序的"已结束"页签只有这一个词，让前端传三个状态再拼
     *               既啰嗦又容易被漏改。
     */
    @GetMapping
    public Result<List<ReservationVO>> list(@RequestParam(required = false) String status) {
        return Result.success(reservationService.listByUser(AuthContext.userId(), status));
    }

    @GetMapping("/{id}")
    public Result<ReservationVO> detail(@PathVariable Long id) {
        return Result.success(reservationService.getDetail(AuthContext.userId(), id));
    }

    /** RESERVED → CANCELLED。只有待签到的订单能取消（§14.4）。 */
    @PostMapping("/{id}/cancel")
    public Result<ReservationVO> cancel(@PathVariable Long id) {
        return Result.success(reservationService.cancel(AuthContext.userId(), id));
    }

    /** USING → AWAY，同时开始计暂离超时（§14.5）。 */
    @PostMapping("/{id}/leave")
    public Result<ReservationVO> leave(@PathVariable Long id) {
        return Result.success(reservationService.leave(AuthContext.userId(), id));
    }

    /** AWAY → USING，清掉暂离超时时间（§14.6）。 */
    @PostMapping("/{id}/return")
    public Result<ReservationVO> back(@PathVariable Long id) {
        return Result.success(reservationService.returnSeat(AuthContext.userId(), id));
    }

    /** USING → COMPLETED，写 study_record 并把座位放回 FREE（§14.7）。 */
    @PostMapping("/{id}/release")
    public Result<ReservationVO> release(@PathVariable Long id) {
        return Result.success(reservationService.release(AuthContext.userId(), id));
    }
}
