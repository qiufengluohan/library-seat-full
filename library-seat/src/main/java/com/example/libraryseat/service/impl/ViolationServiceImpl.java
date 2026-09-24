package com.example.libraryseat.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.libraryseat.entity.Seat;
import com.example.libraryseat.entity.User;
import com.example.libraryseat.entity.Violation;
import com.example.libraryseat.enums.ViolationType;
import com.example.libraryseat.mapper.SeatMapper;
import com.example.libraryseat.mapper.UserMapper;
import com.example.libraryseat.mapper.ViolationMapper;
import com.example.libraryseat.service.ViolationService;
import com.example.libraryseat.util.TimeUtil;
import com.example.libraryseat.vo.PageVO;
import com.example.libraryseat.vo.ViolationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ViolationServiceImpl implements ViolationService {

    private final ViolationMapper violationMapper;
    private final UserMapper userMapper;
    private final SeatMapper seatMapper;

    @Override
    public void create(Long userId, Long seatId, ViolationType type, String description) {
        // violation.seat_id 是 NOT NULL，缺了它这条记录写不进去
        if (seatId == null || type == null) {
            log.warn("违规记录缺少 seatId 或 type，已跳过: seatId={}, type={}", seatId, type);
            return;
        }
        Violation entity = new Violation();
        entity.setUserId(userId);
        entity.setSeatId(seatId);
        entity.setType(type.name());
        entity.setDescription(description);
        entity.setCreatedAt(TimeUtil.now());
        violationMapper.insert(entity);
    }

    @Override
    public PageVO<ViolationVO> page(String type, Long seatId, long page, long size) {
        Set<String> types = parseTypes(type);
        if (types != null && types.isEmpty()) {
            // 传了非法的 type：按"查不到"处理。
            // 不能把条件丢掉退化成全量，那会让筛选器看起来是坏的还给出错误数据。
            return PageVO.empty(page, size);
        }

        IPage<Violation> result = violationMapper.selectPage(
                new Page<>(page, size),
                Wrappers.<Violation>lambdaQuery()
                        .in(types != null, Violation::getType, types == null ? List.of() : types)
                        .eq(seatId != null, Violation::getSeatId, seatId)
                        .orderByDesc(Violation::getId));

        List<Violation> rows = result.getRecords();
        if (rows.isEmpty()) {
            return PageVO.of(result, row -> ViolationVO.of(row, null, null));
        }

        Map<Long, String> studentNames = loadStudentNames(rows);
        Map<Long, String> seatCodes = loadSeatCodes(rows);

        return PageVO.of(result, row -> ViolationVO.of(
                row,
                studentNames.get(row.getUserId()),
                seatCodes.get(row.getSeatId())));
    }

    @Override
    public long countToday() {
        return violationMapper.countSince(TimeUtil.startOfToday());
    }

    /**
     * 违规表只存 user_id / seat_id，但管理端表格要显示"谁、哪个座位"。
     *
     * <p>用两次批量查询而不是 SQL join：一页最多几十条，
     * 两个 IN 查询的开销可以忽略，而 join 要写 XML、要处理
     * "user 被删了但 violation 还在"的左连接语义。
     */
    private Map<Long, String> loadStudentNames(List<Violation> rows) {
        Set<Long> userIds = new HashSet<>();
        for (Violation row : rows) {
            if (row.getUserId() != null) {
                userIds.add(row.getUserId());
            }
        }
        if (userIds.isEmpty()) {
            // 必须用 Collections.emptyMap()，不能图省事写 Map.of()：
            // 后者 get(null) 抛 NPE，而 violation.user_id 是唯一可空的外键
            //（设备自己判定的假占座根本没有登录用户），空表 + null 键一起出现是常态。
            return Collections.emptyMap();
        }
        List<User> users = userMapper.selectList(
                Wrappers.<User>lambdaQuery().in(User::getId, userIds));
        Map<Long, String> names = new HashMap<>();
        for (User user : users) {
            // 昵称可以为空（微信登录不给昵称），这时给 null，
            // 让管理端退回显示 user_id，不要编一个"微信用户"塞进去
            if (user.getNickname() != null && !user.getNickname().isBlank()) {
                names.put(user.getId(), user.getNickname());
            }
        }
        return names;
    }

    private Map<Long, String> loadSeatCodes(List<Violation> rows) {
        Set<Long> seatIds = new HashSet<>();
        for (Violation row : rows) {
            if (row.getSeatId() != null) {
                seatIds.add(row.getSeatId());
            }
        }
        if (seatIds.isEmpty()) {
            return Map.of();
        }
        List<Seat> seats = seatMapper.selectList(
                Wrappers.<Seat>lambdaQuery().in(Seat::getId, seatIds));
        Map<Long, String> codes = new HashMap<>();
        for (Seat seat : seats) {
            codes.put(seat.getId(), seat.getSeatCode());
        }
        return codes;
    }

    /**
     * @return null 表示不过滤；空集合表示"非法值，什么都别返回"。
     *         两种"空"必须区分开，否则非法筛选会静默变成全表扫描。
     */
    private static Set<String> parseTypes(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        Set<String> matched = new HashSet<>();
        for (ViolationType type : ViolationType.values()) {
            if (type.name().equalsIgnoreCase(trimmed)) {
                matched.add(type.name());
                return matched;
            }
        }
        return Set.of();
    }
}
