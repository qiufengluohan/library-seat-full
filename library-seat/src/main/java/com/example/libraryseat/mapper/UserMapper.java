package com.example.libraryseat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.libraryseat.entity.User;

public interface UserMapper extends BaseMapper<User> {

    /**
     * 学号是账号唯一标识：注册查重、登录取用户都走这一个方法。
     *
     * <p>加 {@code LIMIT 1} 是因为这里只用来判断"有没有、是哪一条"，
     * 不需要全表扫；配合 {@code student_no} 上的 UNIQUE 索引，永远最多命中一行。
     */
    default User selectByStudentNo(String studentNo) {
        return selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getStudentNo, studentNo)
                .last("LIMIT 1"));
    }
}
