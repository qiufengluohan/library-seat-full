package com.example.libraryseat.service;

import com.example.libraryseat.vo.OperationLogVO;
import com.example.libraryseat.vo.PageVO;

/**
 * 管理员操作日志。编码规范 §19，方案 §36。
 *
 * <p>方案 §36 的原话是"这是低成本、论文展示效果较好的功能"——
 * 答辩时能翻出"某月某日管理员对 A4-101 执行了强制释放"这种记录，
 * 比口头描述有说服力得多。所以六种管理员动作<b>一个都不要漏</b>。
 */
public interface OperationLogService {

    /* ------------------------------------------------------------
     * operation 字段的取值。
     *
     * 这几个中文字符串是和管理端约定死的：ViolationManage.vue 里
     * OPERATION_TAG 用它们做 key 来决定标签颜色，写错一个字符
     * 前端不会报错，只是所有标签都变成灰色 info，很难发现。
     * 所以统一用这里的常量，不要在调用处手写字符串。
     * ------------------------------------------------------------ */

    String OP_FORCE_RELEASE = "强制释放";
    String OP_CLEAR_ALARM = "消除告警";
    String OP_UPDATE_THRESHOLD = "修改阈值";
    String OP_TEST_BUZZER = "测试蜂鸣器";
    String OP_RFID_BIND = "RFID绑定";
    String OP_RFID_UNBIND = "RFID解绑";

    /**
     * 写一条操作日志。
     *
     * <p><b>永远不抛异常。</b>日志是附属产物，写失败不该让强制释放这种
     * 主流程回滚 —— 座位卡在那里比少一条日志严重得多。
     * 实现里catch 住并 log.error。
     *
     * @param adminId     操作人，来自 {@code AuthContext.adminId()}
     * @param operation   上面六个常量之一
     * @param target      操作对象，用管理员看得懂的写法：座位号 / 设备号 / rfid_uid
     * @param description 补充说明，比如"阈值 2000 -> 2400"
     */
    void record(Long adminId, String operation, String target, String description);

    /**
     * 分页查询（{@code GET /api/admin/logs}），按时间倒序。
     * 管理端只传 page / size，没有筛选条件。
     */
    PageVO<OperationLogVO> page(long page, long size);
}
