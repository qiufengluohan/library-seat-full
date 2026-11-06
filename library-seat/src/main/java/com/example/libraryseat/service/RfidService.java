package com.example.libraryseat.service;

import com.example.libraryseat.dto.RfidBindDTO;
import com.example.libraryseat.vo.RfidBindVO;

import java.util.List;

/**
 * RFID 绑定 / 解绑 / 签到。编码规范 §19，签到流程见 §14.8 与 §22。
 *
 * <p>方案 §26：全馆<b>只有一个共享读卡器</b>（READER_01），装在门口或走廊。
 * 它只上报 {@code rfid_uid}，"这张卡是谁"完全靠 rfid_user 表解析。
 * 所以绑定关系是整个自动签到链路的前提 —— 没绑卡的学生刷了也不会签到。
 */
public interface RfidService {

    /**
     * 绑定关系列表（{@code GET /api/admin/rfid-binds}）。
     *
     * <p>返回<b>数组</b>而不是分页结构：管理端 {@code RfidManage.vue} 直接
     * {@code binds.value = await getRfidBinds()}。绑定数量等于学生数量，
     * 毕设规模下不需要分页。
     *
     * <p>每条要带 studentName —— 管理端表格要显示"这张卡是谁的"，
     * 只有 rfid_uid 的话管理员无法核对。
     */
    List<RfidBindVO> listBinds();

    /**
     * 绑定校园卡（{@code POST /api/admin/rfid-binds}），并写 operation_log（方案 §36）。
     *
     * <p>rfid_uid 和 user_id 在库层<b>都是 UNIQUE</b>，即一人一卡、一卡一人。
     * 因此：
     * <ul>
     *   <li>同一 uid + 同一 userId 重复提交 → 幂等返回已有记录，不报错
     *       （管理员手抖点两次是常态）</li>
     *   <li>uid 已绑给别人 → 409，提示先解绑</li>
     *   <li>该学生已绑了别的卡 → 409，提示先解绑。<b>不要</b>静默覆盖，
     *       否则旧卡会莫名其妙失效，排查起来毫无线索</li>
     *   <li>userId 不存在 → 404</li>
     * </ul>
     */
    RfidBindVO bind(RfidBindDTO dto, Long adminId);

    /**
     * 解绑（{@code DELETE /api/admin/rfid-binds/{uid}}），并写 operation_log。
     *
     * <p>直接删行而不是加"已解绑"标志位 —— 方案 §20 明确不做历史留痕，
     * 需要痕迹的话 operation_log 里已经有了。
     */
    void unbind(String rfidUid, Long adminId);

    /**
     * 刷卡签到。§14.8 / §22。
     *
     * <p>整个方法是<b>静默失败</b>的：卡没绑、绑了但没有待签到订单、
     * 订单已经签过了 —— 一律返回 false，不抛异常。
     * 因为调用方是 OneNET 的设备事件推送，抛异常会变成 HTTP 500，
     * OneNET 可能反复重推同一条事件；而且刷卡人本来也看不到这个响应。
     *
     * @return true 表示这次刷卡确实完成了一次 RESERVED → USING 的签到
     */
    boolean signInByUid(String rfidUid);
}
