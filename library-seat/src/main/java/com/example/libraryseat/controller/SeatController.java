package com.example.libraryseat.controller;

import com.example.libraryseat.common.Result;
import com.example.libraryseat.service.SeatService;
import com.example.libraryseat.vo.SeatDetailVO;
import com.example.libraryseat.vo.SeatVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学生端座位查询。编码规范 §13 / §14.2。
 *
 * <p><b>返回数组，不返回分页对象。</b>全馆座位就几十上百个，
 * 小程序的座位图需要一次性拿到全部才能画出"阅览室 → 楼层 → 座位"的分组，
 * 分页反而要它自己拼。方案 §5 也明确 status / alarm / online 三个合成状态
 * 全部由后端给出，前端不自己根据 pressure_adc 推断。
 *
 * <p>学生端拿到的是 {@code listSeats()}，<b>不含占用者信息</b>；
 * 管理员那一份（{@code AdminSeatController}）才带 student_name。
 * 谁在看决定给多少字段，不要图省事两边共用一个方法。
 */
@RestController
@RequestMapping("/api/seats")
@RequiredArgsConstructor
public class SeatController {

    private final SeatService seatService;

    @GetMapping
    public Result<List<SeatVO>> list() {
        return Result.success(seatService.listSeats());
    }

    /**
     * 座位详情。§13 列了这个接口，小程序目前没调用（列表页已经够用），
     * 但设备实时数据（pressure_adc / pir_state / last_report_at）只有这里有，
     * 留着做详情页或答辩演示都用得上。
     */
    @GetMapping("/{seatId}")
    public Result<SeatDetailVO> detail(@PathVariable Long seatId) {
        return Result.success(seatService.getDetail(seatId));
    }
}
