package com.example.libraryseat.controller;

import com.example.libraryseat.common.Result;
import com.example.libraryseat.security.AuthContext;
import com.example.libraryseat.service.StatisticsService;
import com.example.libraryseat.vo.StatisticsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端个人学习统计（{@code GET /api/statistics/me}，编码规范 §13）。
 *
 * <p>数据全部来自 study_record 表，<b>不实时扫 reservation</b>：
 * 学习时长只在订单结束时落一条记录（离座 / 暂离超时 / 管理员强制释放），
 * 进行中的订单不计入"累计学习"，否则数字会随着定时任务刷新跳来跳去。
 *
 * <p>只统计自己的：userId 从 token 取，不接受查询参数，
 * 不然改成 {@code /api/statistics/me?user_id=2} 就能看别人的学习记录。
 */
@RestController
@RequestMapping("/api/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;

    @GetMapping("/me")
    public Result<StatisticsVO> me() {
        return Result.success(statisticsService.personal(AuthContext.userId()));
    }
}
