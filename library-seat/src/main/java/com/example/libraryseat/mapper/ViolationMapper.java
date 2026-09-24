package com.example.libraryseat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.libraryseat.entity.Violation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

public interface ViolationMapper extends BaseMapper<Violation> {

    /** Dashboard 的"当天违规次数"。 */
    @Select("SELECT COUNT(*) FROM violation WHERE created_at >= #{start}")
    long countSince(@Param("start") LocalDateTime start);
}
