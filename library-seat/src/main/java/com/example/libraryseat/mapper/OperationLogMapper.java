package com.example.libraryseat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.libraryseat.entity.OperationLog;

/**
 * 操作日志只需要写入和分页查询，BaseMapper 加上分页插件就够了，
 * 不写自定义方法（方案 §36 只要求记录，不做检索分析）。
 */
public interface OperationLogMapper extends BaseMapper<OperationLog> {
}
