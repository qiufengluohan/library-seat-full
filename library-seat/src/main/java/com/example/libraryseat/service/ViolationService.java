package com.example.libraryseat.service;

import com.example.libraryseat.enums.ViolationType;
import com.example.libraryseat.vo.PageVO;
import com.example.libraryseat.vo.ViolationVO;

/**
 * 违规记录。编码规范 §19 / §44，方案 §35。
 *
 * <p><b>只有三类，不要扩</b>（§44 明确列出禁止项）：
 * FAKE_OCCUPY / RESERVATION_TIMEOUT / AWAY_TIMEOUT。
 * 不做积分、信用分、黑名单等级、处罚等级。
 *
 * <p>Web 端<b>只查询和展示</b>，没有"手动记一次违规"的接口。
 * 三条记录全部由系统产生：假占座来自设备上报，两个超时来自定时任务。
 */
public interface ViolationService {

    /**
     * 记一条违规。
     *
     * @param userId  可以为 null —— 假占座事件里如果那张卡没绑人，
     *                我们只知道是哪个座位，不知道是谁。这种情况照样要记，
     *                管理端至少能看到"这个位置有人拿东西占座"
     * @param description 给管理员看的中文说明，会原样显示在违规表格里
     */
    void create(Long userId, Long seatId, ViolationType type, String description);

    /**
     * 分页查询（{@code GET /api/admin/violations}）。
     *
     * @param type    FAKE_OCCUPY / RESERVATION_TIMEOUT / AWAY_TIMEOUT，
     *                null 或空串表示不限。<b>非法值按"查不到"处理</b>（返回空页），
     *                不要退化成全量，也不要 400 —— 管理端下拉框不会传错，
     *                真传错了给个空列表比弹红条友好
     * @param seatId  按座位过滤，null 表示不限
     */
    PageVO<ViolationVO> page(String type, Long seatId, long page, long size);

    /** Dashboard 的"今日违规次数" */
    long countToday();
}
