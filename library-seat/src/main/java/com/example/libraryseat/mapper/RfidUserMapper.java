package com.example.libraryseat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.libraryseat.entity.RfidUser;

public interface RfidUserMapper extends BaseMapper<RfidUser> {

    /** 刷卡签到入口：uid → user_id（编码规范 §22）。 */
    default RfidUser findByUid(String rfidUid) {
        return selectOne(Wrappers.<RfidUser>lambdaQuery()
                .eq(RfidUser::getRfidUid, rfidUid)
                .last("LIMIT 1"));
    }

    /** 一个学生只能绑一张卡，绑定时用它查旧绑定（user_id 也是 UNIQUE）。 */
    default RfidUser findByUserId(Long userId) {
        return selectOne(Wrappers.<RfidUser>lambdaQuery()
                .eq(RfidUser::getUserId, userId)
                .last("LIMIT 1"));
    }
}
