package com.example.libraryseat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.libraryseat.entity.AdminUser;

public interface AdminUserMapper extends BaseMapper<AdminUser> {

    default AdminUser selectByUsername(String username) {
        return selectOne(Wrappers.<AdminUser>lambdaQuery()
                .eq(AdminUser::getUsername, username)
                .last("LIMIT 1"));
    }
}
