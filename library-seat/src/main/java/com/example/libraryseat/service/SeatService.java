package com.example.libraryseat.service;

import com.example.libraryseat.dto.OneNetDataDTO;
import com.example.libraryseat.entity.Seat;
import com.example.libraryseat.enums.SeatStatus;
import com.example.libraryseat.vo.SeatDetailVO;
import com.example.libraryseat.vo.SeatVO;

import java.util.List;

/**
 * 座位查询、状态修改、状态合成。编码规范 §19。
 *
 * <h2>这个 Service 握着全系统最重要的一条铁律</h2>
 *
 * {@code seat.status} 是<b>业务状态</b>，只能由预约生命周期和告警事件改写，
 * <b>永远不能由传感器读数反推</b>（方案 §5、§53；编码规范 §18 对前端也是同一要求）。
 * 也就是说：不存在"pressure_adc > 阈值就把 status 改成 USING"这种代码。
 * 压力/红外只进 seat_shadow，业务状态只跟着 reservation 走。
 *
 * <p>唯一的例外是 ALARM：设备判定假占座后上报 alarm_flag，
 * 后端把 status 置为 4，因为小程序的红色闪烁动画是按 {@code status === 4} 触发的
 * （{@code pages/seats/index.wxml} 的 class 取自 statusKey）。
 */
public interface SeatService {

    /**
     * 学生端全馆座位列表（{@code GET /api/seats}）。
     *
     * <p><b>不含 studentName / reserveTime / signTime</b> ——
     * 座位本身是公开的，但"谁坐在哪"是别人的个人信息，
     * 小程序 {@code utils/api.js} 的 normSeat 也不读这三个字段。
     */
    List<SeatVO> listSeats();

    /**
     * 管理端全馆座位列表（{@code GET /api/admin/seats}）。
     *
     * <p>在学生端字段之上补齐"谁在坐"，供座位网格和管理员详情使用。
     * 管理端 {@code stores/seat.js} 直接用返回值建 Map，所以<b>必须返回数组</b>，
     * 不能包成分页结构。
     */
    List<SeatVO> listSeatsForAdmin();

    /**
     * 座位详情（{@code GET /api/admin/seats/{id}}）。
     *
     * <p>含 pressure_adc / pir_state —— §11.3 明确"学生端不返回设备数据"，
     * 所以这个方法只给管理端用，不要挂到 {@code /api/seats/{id}} 上。
     */
    SeatDetailVO getDetail(Long seatId);

    /**
     * 取座位实体，不存在直接抛 404（编码规范 §45）。
     * 预约、签到、强制释放等流程的第一步都用它，省掉每处重复的判空。
     */
    Seat requireSeat(Long seatId);

    /**
     * 改业务状态并广播。
     *
     * <p>只做两件事：写 seat.status、发 SEAT_UPDATE。
     * 订单状态、study_record、violation 都归调用方管 ——
     * 这个方法是所有状态流转的<b>公共出口</b>，别在这里塞业务判断。
     */
    void updateStatus(Long seatId, SeatStatus status);

    /**
     * 带条件的状态更新：只有 seat.status 当前等于 {@code expected} 时才改成 {@code next}。
     * 改成功才广播。
     *
     * <p>这是"两个人同时抢一个座位"的唯一正确防线。
     * {@code SELECT 出来判断再 UPDATE} 在默认隔离级别下必有窗口：
     * 两个请求都读到 FREE，都判断通过，然后都插了订单。
     * 这里把判断压进 UPDATE 的 WHERE 里，靠数据库行锁保证只有一个请求
     * 拿到 affected rows = 1。
     *
     * @return false 表示状态已被别的请求改掉，调用方应回 409「座位已被预约」
     */
    boolean updateStatusIf(Long seatId, SeatStatus expected, SeatStatus next);

    /**
     * 由"当前进行中的订单"反推座位应该处于哪个业务状态。
     *
     * <p>告警恢复时要用：座位被 ALARM(4) 覆盖后，原始状态没有单独存字段，
     * 但订单一直在，按订单状态还原即可 —— RESERVED→1、USING→2、AWAY→3、无订单→0。
     * 这样就不需要给 seat 表加"告警前状态"这种冗余列。
     */
    SeatStatus statusFromReservation(Long seatId);

    /* ------------------------------------------------------------
     * 设备状态（§19 的"设备状态更新"）
     * ------------------------------------------------------------ */

    /**
     * 按 OneNET 设备名找座位。
     *
     * @return 找不到返回 null。<b>不要抛 404</b> —— 调用方是设备上报链路，
     *         共享读卡器 READER_01 本来就不对应任何座位，
     *         抛异常会让 OneNET 收到 500 并反复重推这条数据。
     */
    Seat findByDeviceId(String deviceId);

    /**
     * 把一次设备上报落进 seat_shadow，并处理告警联动（方案 §29）。
     *
     * <p>做四件事：
     * <ol>
     *   <li>影子行不存在就建（seat_id / device_id 都是 UNIQUE），存在就按上报字段更新。
     *       <b>只覆盖报文里确实带了的字段</b>，心跳不带 pressure_adc，
     *       不能拿 null 把已有读数抹掉。</li>
     *   <li>online：报文不带这个字段就视为在线（收到上报本身就证明设备活着），
     *       显式带 {@code false} 的要认 —— 那是 OneNET 的设备状态通知。
     *       last_report_at 取报文时间，解析不出来就用服务器当前时间。</li>
     *   <li>alarm_flag <b>发生翻转</b>时才联动业务状态：
     *           翻成 true → seat.status = ALARM(4) + 记一条 FAKE_OCCUPY 违规 + 广播；
     *           翻成 false → seat.status 按订单还原 + 广播。
     *       只在翻转时动作是必须的：设备每 60 秒上报一次，
     *       每次都联动就会每分钟重复记一条违规、重复广播一遍。</li>
     *   <li>online 发生翻转（任一方向）时补发一条 DEVICE_STATUS。</li>
     * </ol>
     *
     * <p>常规上报<b>绝不改 seat.status</b>（压力和红外只是"设备看到了什么"），
     * 唯一的例外就是上面第 3 条的告警。
     *
     * <p>device_id 找不到座位时只记 warn 就返回，理由同 {@link #findByDeviceId}。
     */
    void applyDeviceReport(OneNetDataDTO dto);

    /**
     * 管理员消除告警（{@code POST /api/admin/seats/{id}/clear-alarm}，§14.10）。
     *
     * <p>§14.10 的字面要求是"只处理 seat_shadow.alarm_flag = 0"，
     * 但<b>还必须把 seat.status 从 ALARM(4) 还原</b>：
     * 小程序的红色闪烁动画是 {@code class="s-{{statusKey}}"}，只看 status===4，
     * 不改 status 的话管理员点了"消除告警"，学生端那个座位会一直闪到下次预约流转。
     *
     * <p>同时写 operation_log（方案 §36），并下发一次显示恢复指令让设备端同步。
     *
     * @param adminId 写日志用，来自 {@code AuthContext.adminId()}
     */
    void clearAlarm(Long seatId, Long adminId);
}
