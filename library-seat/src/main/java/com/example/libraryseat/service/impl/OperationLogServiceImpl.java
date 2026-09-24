package com.example.libraryseat.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.libraryseat.entity.OperationLog;
import com.example.libraryseat.mapper.OperationLogMapper;
import com.example.libraryseat.service.OperationLogService;
import com.example.libraryseat.util.TimeUtil;
import com.example.libraryseat.vo.OperationLogVO;
import com.example.libraryseat.vo.PageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogServiceImpl implements OperationLogService {

    private final OperationLogMapper operationLogMapper;

    @Override
    public void record(Long adminId, String operation, String target, String description) {
        try {
            OperationLog entity = new OperationLog();
            entity.setAdminId(adminId);
            entity.setOperation(operation);
            entity.setTarget(target);
            entity.setDescription(description);
            entity.setCreatedAt(TimeUtil.now());
            operationLogMapper.insert(entity);
        } catch (Exception e) {
            // 日志是附属产物：写不进去也不该让强制释放、消除告警这些主流程回滚。
            // 座位卡在那里比少一条日志严重得多。
            //
            // 这里能安全吞掉异常的前提是 record() 本身没有 @Transactional ——
            // 它跑在调用方的事务里，但没有事务拦截器会因为这条语句失败
            // 把整个事务标记成 rollback-only。
            // 如果哪天给它加上了 @Transactional，这个 catch 就会失效，
            // 外层提交时会抛 UnexpectedRollbackException。
            log.error("写操作日志失败: operation={}, target={}", operation, target, e);
        }
    }

    @Override
    public PageVO<OperationLogVO> page(long page, long size) {
        IPage<OperationLog> result = operationLogMapper.selectPage(
                new Page<>(page, size),
                Wrappers.<OperationLog>lambdaQuery().orderByDesc(OperationLog::getId));
        return PageVO.of(result, OperationLogVO::of);
    }
}
