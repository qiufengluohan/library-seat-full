package com.example.libraryseat.task;

import com.example.libraryseat.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预约超时巡检。编码规范 §24。
 *
 * <h2>两条规则</h2>
 *
 * <ul>
 *   <li>A：{@code status=RESERVED AND reserve_expire_at < now} → TIMEOUT，座位回 FREE</li>
 *   <li>B：{@code status=AWAY AND leave_expire_at < now} → COMPLETED，座位回 FREE，
 *       并写一条 study_record（暂离前那段学习时间要算进累计时长）</li>
 * </ul>
 *
 * <p>每次状态变化都同时落库 + WebSocket 广播，这两件事在
 * {@code ReservationServiceImpl} 里，不在这里。
 *
 * <h2>为什么是轮询而不是延迟队列</h2>
 *
 * §24 明确"不使用 Redis 延迟队列"，方案 §49 也把 Redis / Kafka / RabbitMQ 划在范围外。
 * 全馆座位就几十个，一分钟一次的索引扫描成本可以忽略，
 * 换来的是<b>没有额外中间件要装、要配、要在答辩现场解释</b>。
 *
 * <h2>为什么用 fixedDelay 而不是 cron</h2>
 *
 * 倒计时是相对时间（预约后 15 分钟），不是"每天几点"，cron 表达不了。
 * fixedDelay 还保证上一轮跑完才开始计下一轮的 60 秒，
 * 万一某轮因为数据库慢跑了 5 秒，也不会出现两轮叠在一起抢同一批订单。
 *
 * <p>另外 Spring 默认的调度线程池只有<b>一个线程</b>，
 * 所以本任务和 {@link DeviceOfflineTask} 天然串行、永不并发。
 * 两个都是毫秒级的库内扫描，不需要为了并行去配线程池。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationTimeoutTask {

    /** 60 秒：和设备心跳同频，也是小程序倒计时的显示精度。 */
    private static final long FIXED_DELAY_MS = 60_000L;

    private final ReservationService reservationService;

    /**
     * 不加 try/catch：Spring 的 {@code ReschedulingRunnable} 会捕获异常、
     * 记 ERROR 日志，然后<b>照常安排下一次执行</b> —— 一轮失败不会让巡检永久停摆。
     * 逐条订单的异常在 Service 里已经各自兜住了（一条脏数据不影响其余订单）。
     */
    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    public void sweep() {
        int timeouts = reservationService.releaseExpiredReservations();
        int aways = reservationService.releaseExpiredAway();
        if (timeouts > 0 || aways > 0) {
            log.info("超时巡检: 未签到释放 {} 单，暂离超时释放 {} 单", timeouts, aways);
        }
    }
}
