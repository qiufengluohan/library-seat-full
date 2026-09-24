package com.example.libraryseat.service;

import com.example.libraryseat.dto.ReservationCreateDTO;
import com.example.libraryseat.vo.ReservationVO;

import java.util.List;

/**
 * 预约全流程。编码规范 §19 / §21 / §23 / §24。
 *
 * <h2>状态机（§5.2）</h2>
 *
 * <pre>
 *  create   ┌──────────┐ RFID签到 ┌───────┐ leave ┌──────┐ return
 * ─────────▶│ RESERVED │─────────▶│ USING │──────▶│ AWAY │──────┐
 *           └────┬─────┘          └───┬───┘       └──┬───┘      │
 *      超时/取消 │              release│         超时 │          │
 *                ▼                     ▼              ▼          │
 *          TIMEOUT/CANCELLED      COMPLETED      COMPLETED       │
 *                                                              ┌─┘
 *                                          USING ◀─────────────┘
 * </pre>
 *
 * 每一次订单状态变化都必须同步两件事：改 {@code seat.status}、WebSocket 广播。
 * 漏掉广播，管理端要等到手动刷新才看得到；漏掉 seat.status，
 * 小程序的座位图会和真实占用情况不一致。
 *
 * <h2>并发</h2>
 *
 * 两个人同时抢同一个座位是本项目唯一真正的竞态。靠
 * {@code updateStatusIf(seatId, expectFree, thenReserved)} 这种
 * "带条件的 UPDATE" 来保证只有一个请求能改成功（affected rows = 1），
 * 另一个直接 409。<b>不要</b>用"先 SELECT 再判断再 UPDATE"来防重 ——
 * 那个写法在默认隔离级别下必然有窗口。
 */
public interface ReservationService {

    /* ------------------------------------------------------------
     * 学生端
     * ------------------------------------------------------------ */

    /**
     * 创建预约。§14.3 / §21，必须走事务。
     *
     * <p>校验顺序照抄 §21 伪代码：座位存在(404) → 座位是 FREE(409) →
     * 当前用户没有进行中的预约(409) → 建单 → 座位转 RESERVED → 广播。
     *
     * <p>{@code reserve_expire_at = now + seat.reserve-timeout-minutes}，
     * 15 分钟不刷卡签到就由定时任务判 TIMEOUT（§24 A）。
     */
    ReservationVO create(Long userId, ReservationCreateDTO dto);

    /** §14.4：只有 RESERVED 能取消。USING / AWAY / COMPLETED 一律 409 */
    ReservationVO cancel(Long userId, Long reservationId);

    /** §14.5：USING → AWAY，写 leave_time 和 leave_expire_at */
    ReservationVO leave(Long userId, Long reservationId);

    /** §14.6：AWAY → USING，写 return_time */
    ReservationVO returnSeat(Long userId, Long reservationId);

    /**
     * §14.7：USING → COMPLETED，座位回 FREE，并生成 study_record。
     *
     * <p>时长 = sign_time → release_time（§23）。中间发生过暂离也<b>不拆分</b>，
     * 按整个 USING 周期算 —— §23 明确要求"不要现在就复杂化"。
     */
    ReservationVO release(Long userId, Long reservationId);

    /**
     * 当前进行中的预约（{@code GET /api/reservations/current}）。
     *
     * @return 没有则返回 null。<b>不要</b>抛 404 ——
     *         小程序首页每次进来都调这个接口，"没有预约"是常态而不是错误，
     *         抛异常会让首页天天弹红条。Result.data 为 null 时
     *         小程序 {@code getCurrentReservation} 会正常返回 null。
     */
    ReservationVO getCurrent(Long userId);

    /**
     * 我的预约列表。
     *
     * @param statusFilter ALL / RESERVED / USING / AWAY / FINISHED / 具体枚举名。
     *        FINISHED = COMPLETED + CANCELLED + TIMEOUT，<b>由后端归并</b>，
     *        小程序只传这一个词（§5.2）。传非法值返回空列表，不报错。
     */
    List<ReservationVO> listByUser(Long userId, String statusFilter);

    /**
     * 预约详情。
     *
     * @throws com.example.libraryseat.common.BusinessException 订单不存在 → 404；
     *         订单不属于该用户 → 403。不能返回 404 掩盖越权，
     *         但也不要让别人看到"这单存在"以外的信息。
     */
    ReservationVO getDetail(Long userId, Long reservationId);

    /* ------------------------------------------------------------
     * 管理员 / 系统
     * ------------------------------------------------------------ */

    /**
     * RFID 签到。§14.8 / §22：RESERVED → USING，写 sign_time，座位转 USING，广播。
     *
     * <p>由 {@link RfidService} 调用，学生端没有手动签到接口 ——
     * 签到必须刷校园卡，这是"防代签"的唯一手段。
     *
     * @return 签到后的订单；该用户没有 RESERVED 订单时返回 null（§22 伪代码是直接 return，
     *         因为刷卡时人可能只是路过，不该报错）
     */
    ReservationVO signIn(Long userId);

    /**
     * 管理员强制释放。§14.9，七步一步都不能少：
     * 查座位 → 查当前订单 → 关单 → 座位 FREE → 必要时 study_record →
     * operation_log → OneNET 下发显示恢复 → 广播。
     *
     * <p>"必要时"指：订单已经签过到（有 sign_time）才生成学习记录，
     * 只预约没签到的强制释放不该算学习时长。
     *
     * @param adminId 写进 operation_log 的管理员 ID
     * @return 被关闭的订单；座位本来就是空的则返回 null，同样要写日志（管理员点了就得有记录）
     */
    ReservationVO forceRelease(Long seatId, Long adminId);

    /**
     * §24 A：RESERVED 且 reserve_expire_at 已过 → TIMEOUT，座位回 FREE，记 RESERVATION_TIMEOUT 违规。
     *
     * @return 处理条数，供定时任务打日志
     */
    int releaseExpiredReservations();

    /**
     * §24 B：AWAY 且 leave_expire_at 已过 → COMPLETED，座位回 FREE，
     * 生成 study_record（人确实来学过），记 AWAY_TIMEOUT 违规。
     *
     * @return 处理条数
     */
    int releaseExpiredAway();
}
