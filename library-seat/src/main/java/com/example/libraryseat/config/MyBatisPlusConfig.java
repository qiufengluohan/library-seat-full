package com.example.libraryseat.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件。
 *
 * <p><b>分页拦截器必须注册</b>，否则 {@code Page<T>} 查询会静默退化成
 * "查全表然后把整个列表塞进 records，total 为 0"：接口照样返回 200，
 * 管理端的表格看着也有数据，只是分页器永远是 1 页。
 * 这种问题在编译期和启动期都不报错，只有点到第二页才发现。
 */
@Configuration
public class MyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限，防止管理端传 size=100000 把全表拖出来
        pagination.setMaxLimit(500L);
        // 请求页码超过总页数时返回空页，而不是回到第一页重复给数据
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
