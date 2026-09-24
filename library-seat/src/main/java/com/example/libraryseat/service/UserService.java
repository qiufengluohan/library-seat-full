package com.example.libraryseat.service;

import com.example.libraryseat.dto.UserUpdateDTO;
import com.example.libraryseat.dto.WechatLoginDTO;
import com.example.libraryseat.entity.User;
import com.example.libraryseat.vo.UserVO;
import com.example.libraryseat.vo.WechatLoginVO;

/**
 * 用户查询与创建。编码规范 §19。
 *
 * <p>本项目的学生<b>没有注册流程</b>：小程序 wx.login 拿到 code，
 * 后端换 openid，查不到就当场建一条 user（§14.1 第 2、3 步）。
 * 所以这里只有"按 openid 找 / 找不到就建"，不要加密码、不要加手机号。
 */
public interface UserService {

    /**
     * 学生微信登录（{@code POST /api/auth/wechat-login}），§14.1 的五步全在这里：
     * code 换 openid → 查 user → 没有则建 → 签 JWT → 返回扁平的 {@link WechatLoginVO}。
     *
     * <p>放在 UserService 而不是单开一个 AuthService：§7 的服务清单是固定的十二个，
     * 里面没有 AuthService，而 {@link AdminService#login} 正是管理员侧的对称入口。
     *
     * <p>code 是一次性的（5 分钟过期、用过即废），所以重复提交同一个 code
     * 会得到 401，小程序重新 wx.login 即可，不要在后端做任何重试。
     */
    WechatLoginVO loginByCode(WechatLoginDTO dto);

    /**
     * 按主键取用户。
     *
     * @return 不存在时返回 null；需要"必须存在"语义的调用方自己抛 404，
     *         因为有些场景（比如给违规记录补昵称）查不到就该留空而不是报错
     */
    User getById(Long userId);

    User findByOpenid(String openid);

    /**
     * 微信登录用：openid 已存在就返回，不存在就插入一条新用户。
     *
     * <p>必须保证并发下不会插出两条同 openid 的记录 ——
     * 数据库 {@code user.openid} 上有唯一索引兜底，
     * 撞索引时重查一次即可，见实现类。
     */
    User findOrCreateByOpenid(String openid);

    /** 我的资料。带 rfid_uid / rfid_bind_time，小程序"我的"页一次拿全 */
    UserVO getProfile(Long userId);

    /**
     * 改昵称 / 头像。
     *
     * <p>只有这两个字段可改 —— openid 是身份标识，
     * userId 由 token 决定，都不接受前端传入。
     */
    UserVO updateProfile(Long userId, UserUpdateDTO dto);
}
