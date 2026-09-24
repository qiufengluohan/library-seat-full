package com.example.libraryseat.service;

import com.example.libraryseat.vo.SeatVO;
import com.example.libraryseat.websocket.WsMessage;

/**
 * 实时广播。编码规范 §19 单列的一个 Service，§18 的最后一环。
 *
 * <h2>为什么要包一层，而不是让业务 Service 直接调 SeatWebSocketHandler</h2>
 *
 * {@link #broadcastSeatUpdate(Long)} 只接收一个 seatId，消息内容<b>现查数据库</b>。
 * 这样能杜绝一类很典型的 bug：业务代码改了 status 之后，
 * 手动 new 一个消息、把"它以为的新值"填进去，一旦中间还有别的分支改过状态，
 * 推给前端的就和库里的不一致 —— 而管理端是<b>增量合并</b>（patchSeat），
 * 收到错值就会一直错到下次刷新。
 *
 * <p>统一"以库为准现查现发"之后，广播永远和数据库一致，
 * 业务 Service 也只需要说"这个座位变了"，不用关心消息长什么样。
 */
public interface WebSocketService {

    /**
     * 广播座位的完整当前状态（§18.1 SEAT_UPDATE）。
     *
     * <p>座位不存在或从未上报过影子行时静默跳过：广播失败不该让业务事务回滚，
     * 数据库才是事实来源，前端刷新一次就能对齐。
     */
    void broadcastSeatUpdate(Long seatId);

    /** 广播告警标志（§18.3 ALARM）。只在 alarm 字段变化时用 */
    void broadcastAlarm(Long seatId, boolean alarm);

    /** 广播设备上下线（§18.2 DEVICE_STATUS）。<b>不带 status</b>，离线不是业务状态（§5.3） */
    void broadcastDeviceStatus(Long seatId, boolean online);

    /** 便捷方法：直接广播一条已经构造好的消息，用于调用方已确切知道内容的场景 */
    void broadcast(WsMessage message);
}
