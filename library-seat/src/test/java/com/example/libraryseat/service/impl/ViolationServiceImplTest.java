package com.example.libraryseat.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.libraryseat.entity.Seat;
import com.example.libraryseat.entity.User;
import com.example.libraryseat.entity.Violation;
import com.example.libraryseat.mapper.SeatMapper;
import com.example.libraryseat.mapper.UserMapper;
import com.example.libraryseat.mapper.ViolationMapper;
import com.example.libraryseat.vo.PageVO;
import com.example.libraryseat.vo.ViolationVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 盯的是一行线上必然踩到的空指针。
 *
 * <p>{@code violation.user_id} 是九张表里唯一可空的外键 —— 设备自己判定的假占座
 * 压根没有登录用户。而 {@code Map.of()} 这种不可变 Map 的 {@code get(null)}
 * <b>抛 NPE 而不是返回 null</b>（{@code Collections.emptyMap()} 才返回 null），
 * 所以"一条带用户的违规都没有" + "这一行的 user_id 是 null"同时成立时，
 * {@code GET /api/admin/violations} 整个 500。
 *
 * <p>这个组合是假占座的常态而非边角，写这个测试是因为它在真机联调之前不可能被发现。
 */
class ViolationServiceImplTest {

    private final ViolationMapper violationMapper = mock(ViolationMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final SeatMapper seatMapper = mock(SeatMapper.class);
    private final ViolationServiceImpl service =
            new ViolationServiceImpl(violationMapper, userMapper, seatMapper);

    private void stubPage(List<Violation> rows) {
        when(violationMapper.selectPage(any(), any(Wrapper.class))).thenAnswer(inv -> {
            IPage<Violation> page = inv.getArgument(0);
            page.setRecords(rows);
            page.setTotal(rows.size());
            return page;
        });
    }

    private static Violation violation(Long id, Long userId, Long seatId, String type) {
        Violation v = new Violation();
        v.setId(id);
        v.setUserId(userId);
        v.setSeatId(seatId);
        v.setType(type);
        v.setDescription("压力持续而红外无人");
        v.setCreatedAt(LocalDateTime.of(2026, 9, 21, 10, 30));
        return v;
    }

    /** 这就是回归点：user_id 为 null 且库里没有任何有用户的违规。 */
    @Test
    void nullUserIdWithNoStudentsDoesNotThrow() {
        stubPage(List.of(violation(1L, null, 1L, "FAKE_OCCUPY")));
        when(seatMapper.selectList(any())).thenReturn(List.of(seat(1L, "A4-203")));

        PageVO<ViolationVO> page = service.page(null, null, 1, 20);

        assertEquals(1, page.getRecords().size());
        ViolationVO vo = page.getRecords().get(0);
        assertNull(vo.getStudentName(), "没有登录用户，昵称就该是 null，不能编一个");
        assertEquals("A4-203", vo.getSeatCode());
        assertNull(vo.getUserId());
    }

    /** 库里<b>有</b>别的带用户的违规时，null 那行也必须正常返回而不是炸掉。 */
    @Test
    void nullUserIdStillFineWhenOtherRowsHaveUsers() {
        stubPage(List.of(
                violation(1L, null, 1L, "FAKE_OCCUPY"),
                violation(2L, 7L, 1L, "AWAY_TIMEOUT")));
        when(userMapper.selectList(any())).thenReturn(List.of(user(7L, "张三")));
        when(seatMapper.selectList(any())).thenReturn(List.of(seat(1L, "A4-203")));

        PageVO<ViolationVO> page = service.page(null, null, 1, 20);

        assertEquals("张三", page.getRecords().get(1).getStudentName());
        assertNull(page.getRecords().get(0).getStudentName());
    }

    /** 座位被删了但违规还在 —— 管理端要能看到违规，座位号留空。 */
    @Test
    void missingSeatYieldsNullSeatCode() {
        stubPage(List.of(violation(1L, null, 99L, "FAKE_OCCUPY")));
        when(seatMapper.selectList(any())).thenReturn(List.of());

        PageVO<ViolationVO> page = service.page(null, null, 1, 20);

        assertNotNull(page.getRecords().get(0));
        assertNull(page.getRecords().get(0).getSeatCode());
    }

    @Test
    void emptyResultIsAnEmptyPage() {
        stubPage(List.of());

        PageVO<ViolationVO> page = service.page(null, null, 1, 20);

        assertEquals(0, page.getRecords().size());
    }

    /** 非法 type 返回空页，而不是退化成全表 —— 这条和上面的 NPE 一样是"筛选项看起来坏了"的来源。 */
    @Test
    void unknownTypeReturnsEmptyPageNotEverything() {
        PageVO<ViolationVO> page = service.page("CHEATING", null, 1, 20);

        assertEquals(0, page.getRecords().size());
        assertEquals(0, page.getTotal());
    }

    @Test
    void knownTypeIsCaseInsensitive() {
        stubPage(List.of(violation(1L, null, 1L, "FAKE_OCCUPY")));
        when(seatMapper.selectList(any())).thenReturn(List.of(seat(1L, "A4-203")));

        assertEquals(1, service.page("fake_occupy", null, 1, 20).getRecords().size());
    }

    /**
     * 把根因本身钉住：一旦有人把 {@code Collections.emptyMap()} "简化"回
     * {@code Map.of()}，这个测试会先红，而不是等到接口 500。
     */
    @Test
    void immutableMapOfThrowsOnNullKeyButEmptyMapDoesNot() {
        assertThrows(NullPointerException.class, () -> Map.of().get(null));
        assertThrows(NullPointerException.class, () -> Map.of(1L, "a").get(null));
        assertNull(java.util.Collections.emptyMap().get(null));
    }

    private static Seat seat(Long id, String code) {
        Seat seat = new Seat();
        seat.setId(id);
        seat.setSeatCode(code);
        return seat;
    }

    private static User user(Long id, String nickname) {
        User user = new User();
        user.setId(id);
        user.setNickname(nickname);
        return user;
    }
}
