package com.example.libraryseat.controller;

import com.example.libraryseat.common.Result;
import com.example.libraryseat.service.OperationLogService;
import com.example.libraryseat.service.StatisticsService;
import com.example.libraryseat.service.ViolationService;
import com.example.libraryseat.vo.OperationLogVO;
import com.example.libraryseat.vo.PageVO;
import com.example.libraryseat.vo.StatisticsVO;
import com.example.libraryseat.vo.ViolationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端三个只读查询接口：统计、违规、操作日志。编码规范 §13，方案 §35 / §36。
 *
 * <h2>为什么这三个凑在一个类里</h2>
 *
 * §7 只给了十个 controller，没有 ViolationController / LogController。
 * 这三个的共同点是<b>纯查询、不改任何状态</b>，都服务于管理端那个
 * "违规与日志"页面（{@code ViolationManage.vue} 一次调这三个），
 * 放在一起比按表拆成三个类更符合实际用法。
 *
 * <h2>违规只读</h2>
 *
 * 方案 §35：违规记录由系统自动产生（假占座 / 预约超时 / 暂离超时），
 * <b>不提供人工新增、修改、删除</b>，也没有积分、信用分、黑名单分级。
 * 所以这里一个写接口都没有 —— 不是漏了，是刻意不做：
 * 能被管理员随手删掉的记录，答辩时就说明不了"检测有效"。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminStatisticsController {

    /** 页码下限。传 0 或负数会让 MyBatis-Plus 算出负 offset，SQL 直接语法错误。 */
    private static final long MIN_PAGE = 1L;

    private final StatisticsService statisticsService;
    private final ViolationService violationService;
    private final OperationLogService operationLogService;

    /**
     * 全馆累计统计。不接受任何参数 —— 管理端只展示"累计学习时长 / 次数 / 人均"，
     * 没有按日期筛选的需求；真要做时间范围，规范 §49 已经把它划在范围外了。
     */
    @GetMapping("/statistics")
    public Result<StatisticsVO> statistics() {
        return Result.success(statisticsService.overall());
    }

    /**
     * 违规记录分页查询。
     *
     * @param type   三个枚举名之一（FAKE_OCCUPY / RESERVATION_TIMEOUT / AWAY_TIMEOUT），
     *               忽略大小写；为空表示不筛。<b>传别的值一律返回空页</b>，
     *               不会退化成全量 —— 那样筛选器看着像坏了却给出错误数据，更难发现
     * @param seatId 查询参数名是 {@code seat_id}，<b>必须显式指定</b>：
     *               Jackson 的 SNAKE_CASE 只管请求体和响应体，
     *               管不到 @RequestParam，写 seatId 前端就永远筛不出来
     */
    @GetMapping("/violations")
    public Result<PageVO<ViolationVO>> violations(@RequestParam(required = false) String type,
                                                  @RequestParam(name = "seat_id", required = false) Long seatId,
                                                  @RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long size) {
        return Result.success(
                violationService.page(type, seatId, Math.max(page, MIN_PAGE), size));
    }

    /** 管理员操作日志分页查询，只按时间倒序，没有筛选条件（方案 §36）。 */
    @GetMapping("/logs")
    public Result<PageVO<OperationLogVO>> logs(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "20") long size) {
        return Result.success(operationLogService.page(Math.max(page, MIN_PAGE), size));
    }
}
