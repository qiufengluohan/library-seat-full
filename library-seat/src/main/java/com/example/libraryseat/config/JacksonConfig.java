package com.example.libraryseat.config;

import com.example.libraryseat.util.TimeUtil;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * REST 层的时间格式。
 *
 * <p><b>为什么不能只靠 {@code spring.jackson.date-format}：</b>
 * 那个属性只作用于 {@code java.util.Date}，对 JSR-310 的
 * {@code LocalDateTime} 无效。不显式注册的话，本项目所有实体字段都是
 * LocalDateTime，输出会变成 ISO 数组或带 T 的字符串
 * （{@code 2026-09-21T09:05:00}），而小程序 docs/04 §2.3 和管理端表格
 * 期望的是 {@code 2026-09-21 09:05:00}。
 *
 * <p>用 {@link Jackson2ObjectMapperBuilderCustomizer} 而不是自己 new 一个
 * ObjectMapper Bean，是为了<b>保留 Boot 自动配置</b>：
 * {@code property-naming-strategy: SNAKE_CASE} 是从 application.yml 读进来的，
 * 自建 ObjectMapper 会把它丢掉，那样四端字段名会同时崩（编码规范 §4）。
 *
 * <p>注意 WebSocket 的 timestamp 是 epoch 秒（§18），走的是
 * {@code WsMessage} 里的 long 字段，和这里的格式化互不影响。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeCustomizer() {
        return builder -> builder
                .serializerByType(LocalDateTime.class,
                        new LocalDateTimeSerializer(TimeUtil.REST_FORMATTER))
                .deserializerByType(LocalDateTime.class,
                        new LocalDateTimeDeserializer(TimeUtil.REST_FORMATTER));
    }
}
