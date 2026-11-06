package com.example.libraryseat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.libraryseat.entity.RfidUser;

public interface RfidUserMapper extends BaseMapper<RfidUser> {

    /**
     * 刷卡签到入口：uid → user_id（编码规范 §22）。
     *
     * <p>⚠️ 必须**大小写不敏感**：设备端 `%02X` 发的是大写十六进制（A1B2C3D4），
     * 而管理员在绑卡页手输的可能是小写或带空格/冒号。直接 `eq` 会让"卡明明绑了
     * 却刷不进"这种问题极难排查。这里统一按大写比较，入库时另由
     * {@code RfidServiceImpl.normalize} 归一化。
     */
    default RfidUser findByUid(String rfidUid) {
        String key = rfidUid == null ? "" : rfidUid.trim().toUpperCase(java.util.Locale.ROOT);
        return selectOne(Wrappers.<RfidUser>lambdaQuery()
                .apply("UPPER(rfid_uid) = {0}", key)
                .last("LIMIT 1"));
    }

    /**
     * 一个学生只能绑一张卡，绑定时用它查旧绑定（user_id 也是 UNIQUE）。
     */
    default RfidUser findByUserId(Long userId) {
        return selectOne(Wrappers.<RfidUser>lambdaQuery()
                .eq(RfidUser::getUserId, userId)
                .last("LIMIT 1"));
    }
}
