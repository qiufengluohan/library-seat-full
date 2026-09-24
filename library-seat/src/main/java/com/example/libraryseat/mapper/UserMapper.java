package com.example.libraryseat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.libraryseat.entity.User;

public interface UserMapper extends BaseMapper<User> {

    /** openid 是账号唯一标识，微信登录时靠它决定"取已有用户"还是"新建"。 */
    default User selectByOpenid(String openid) {
        return selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getOpenid, openid)
                .last("LIMIT 1"));
    }
}
