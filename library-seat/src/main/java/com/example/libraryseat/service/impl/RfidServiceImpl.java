package com.example.libraryseat.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.libraryseat.common.BusinessException;
import com.example.libraryseat.dto.RfidBindDTO;
import com.example.libraryseat.entity.RfidUser;
import com.example.libraryseat.entity.User;
import com.example.libraryseat.mapper.RfidUserMapper;
import com.example.libraryseat.mapper.UserMapper;
import com.example.libraryseat.service.OperationLogService;
import com.example.libraryseat.service.ReservationService;
import com.example.libraryseat.service.RfidService;
import com.example.libraryseat.util.TimeUtil;
import com.example.libraryseat.vo.RfidBindVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RfidServiceImpl implements RfidService {

    private final RfidUserMapper rfidUserMapper;
    private final UserMapper userMapper;
    private final ReservationService reservationService;
    private final OperationLogService operationLogService;

    @Override
    public List<RfidBindVO> listBinds() {
        List<RfidUser> rows = rfidUserMapper.selectList(
                Wrappers.<RfidUser>lambdaQuery().orderByDesc(RfidUser::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> nicknames = loadNicknames(rows);
        List<RfidBindVO> result = new ArrayList<>(rows.size());
        for (RfidUser row : rows) {
            result.add(RfidBindVO.of(row, nicknames.get(row.getUserId())));
        }
        return result;
    }

    @Override
    public RfidBindVO bind(RfidBindDTO dto, Long adminId) {
        String uid = normalize(dto.getRfidUid());
        Long userId = dto.getUserId();
        if (uid.isEmpty()) {
            throw BusinessException.badRequest("RFID 卡号不能为空");
        }
        User student = userMapper.selectById(userId);
        if (student == null) {
            throw BusinessException.notFound("学生不存在: " + userId);
        }

        RfidUser byUid = rfidUserMapper.findByUid(uid);
        if (byUid != null) {
            if (byUid.getUserId().equals(userId)) {
                // 管理员手抖点两次是常态，同一对 uid + userId 直接返回已有记录
                return RfidBindVO.of(byUid, nicknameOf(student));
            }
            throw BusinessException.conflict("该卡已绑定其他学生，请先解绑");
        }
        RfidUser byUser = rfidUserMapper.findByUserId(userId);
        if (byUser != null) {
            // 绝不静默覆盖：旧卡会莫名其妙失效，而读卡器只上报 uid，
            // 出问题时日志里连"哪张卡失效了"都查不到
            throw BusinessException.conflict("该学生已绑定卡 " + byUser.getRfidUid() + "，请先解绑");
        }

        RfidUser entity = new RfidUser();
        entity.setRfidUid(uid);
        entity.setUserId(userId);
        // bind_time 有 DDL 默认值，但 MyBatis-Plus 插入后不会回读，
        // 这里显式赋值，返回给管理端的 VO 才有绑定时间
        entity.setBindTime(TimeUtil.now());
        try {
            rfidUserMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 上面两次查重和这次插入之间有窗口，两个管理员同时绑同一张卡会走到这里
            throw BusinessException.conflict("该卡或该学生的绑定已存在，请刷新后重试");
        }

        String studentName = nicknameOf(student);
        operationLogService.record(adminId, OperationLogService.OP_RFID_BIND, uid,
                "绑定学生 " + (studentName == null ? userId : studentName));
        return RfidBindVO.of(entity, studentName);
    }

    @Override
    public void unbind(String rfidUid, Long adminId) {
        String uid = normalize(rfidUid);
        RfidUser existing = uid.isEmpty() ? null : rfidUserMapper.findByUid(uid);
        if (existing == null) {
            throw BusinessException.notFound("绑定不存在: " + uid);
        }
        rfidUserMapper.deleteById(existing.getId());
        operationLogService.record(adminId, OperationLogService.OP_RFID_UNBIND, uid,
                "解绑学生 " + existing.getUserId());
    }

    @Override
    public boolean signInByUid(String rfidUid) {
        String uid = normalize(rfidUid);
        if (uid.isEmpty()) {
            log.warn("RFID 事件缺少 uid，已忽略");
            return false;
        }
        RfidUser bind = rfidUserMapper.findByUid(uid);
        if (bind == null) {
            log.info("刷了未绑定的卡: rfidUid={}", uid);
            return false;
        }
        try {
            return reservationService.signIn(bind.getUserId()) != null;
        } catch (Exception e) {
            // 调用方是 OneNET 的事件推送，抛出去会变成 500 并被反复重推同一条事件。
            // 刷卡人也看不到这个响应，所以只留日志。
            log.error("RFID 签到失败: rfidUid={}, userId={}", uid, bind.getUserId(), e);
            return false;
        }
    }

    /**
     * 只做 trim，<b>不改大小写</b>。
     *
     * <p>读卡器上报的 uid 是什么形态就存什么形态，绑定时若统一转大写，
     * 而设备上报的是小写，{@code findByUid} 就永远查不到，
     * 症状是"明明绑了卡，刷了却没签到"—— 极难排查。
     */
    private static String normalize(String rfidUid) {
        return rfidUid == null ? "" : rfidUid.trim();
    }

    /** 昵称为空时返回 null，管理端表格会退回显示 user_id。 */
    private static String nicknameOf(User student) {
        return student.getNickname() == null || student.getNickname().isBlank()
                ? null
                : student.getNickname();
    }

    /** 批量取昵称，避免"一行绑定一次 user 查询"的 N+1。 */
    private Map<Long, String> loadNicknames(List<RfidUser> rows) {
        Set<Long> userIds = new HashSet<>();
        for (RfidUser row : rows) {
            if (row.getUserId() != null) {
                userIds.add(row.getUserId());
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<User> users = userMapper.selectList(Wrappers.<User>lambdaQuery().in(User::getId, userIds));
        Map<Long, String> names = new HashMap<>(users.size() * 2);
        for (User user : users) {
            String nickname = nicknameOf(user);
            if (nickname != null) {
                names.put(user.getId(), nickname);
            }
        }
        return names;
    }
}
