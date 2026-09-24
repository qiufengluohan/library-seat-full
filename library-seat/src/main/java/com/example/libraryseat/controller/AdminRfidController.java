package com.example.libraryseat.controller;

import com.example.libraryseat.common.Result;
import com.example.libraryseat.dto.RfidBindDTO;
import com.example.libraryseat.security.AuthContext;
import com.example.libraryseat.service.RfidService;
import com.example.libraryseat.vo.RfidBindVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RFID 卡与学生绑定。编码规范 §13，方案 §26。
 *
 * <p>这是<b>只有管理员能做</b>的操作：签到靠刷卡完成，谁能在系统里
 * 把一张卡挂到某个学生名下，谁就能替那个人签到。所以绑定/解绑
 * 一律走 {@code /api/admin/**}，学生端没有对应接口。
 *
 * <p>列表<b>不分页</b>：绑定关系总量等于学生数，管理端的表格一次渲染得完，
 * 分页只会让"找某张卡绑没绑"变成翻好几页的事。
 */
@RestController
@RequestMapping("/api/admin/rfid-binds")
@RequiredArgsConstructor
public class AdminRfidController {

    private final RfidService rfidService;

    @GetMapping
    public Result<List<RfidBindVO>> list() {
        return Result.success(rfidService.listBinds());
    }

    /**
     * 绑定。重复提交同一对 (rfid_uid, user_id) 是幂等的，返回已有的那条；
     * 卡已绑别人、或该学生已绑别的卡，都是 409 —— <b>不静默覆盖</b>，
     * 覆盖会让原来那张卡突然失效，管理员却看不到任何提示。
     */
    @PostMapping
    public Result<RfidBindVO> bind(@Valid @RequestBody RfidBindDTO dto) {
        return Result.success(rfidService.bind(dto, AuthContext.adminId()));
    }

    /**
     * 解绑。路径变量是 rfid_uid <b>字符串</b>（如 {@code A1B2C3D4}），
     * 不是 rfid_user 表的主键：管理端表格里能复制到的就是卡号，
     * 让它先查一次 id 再删是多余的一跳。
     */
    @DeleteMapping("/{uid}")
    public Result<Void> unbind(@PathVariable String uid) {
        rfidService.unbind(uid, AuthContext.adminId());
        return Result.success();
    }
}
