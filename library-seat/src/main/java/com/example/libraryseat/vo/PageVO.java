package com.example.libraryseat.vo;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;
import java.util.function.Function;

/**
 * 分页返回。
 *
 * <p>字段名对齐 MyBatis-Plus 的 IPage（records / total / current / size / pages），
 * 因为 Web 管理端的 readPage() 就是按 {@code records} + {@code total} 取的
 * （library-admin/src/views/ViolationManage.vue）。同时也接受裸数组，
 * 但这里统一返回分页对象，别让前端猜。
 *
 * <p>不直接把 IPage 序列化出去：IPage 还带 orders / optimizeCountSql /
 * searchCount 这些内部字段，对前端是噪音。
 */
@Data
public class PageVO<T> {

    private List<T> records;

    private long total;

    private long current;

    private long size;

    private long pages;

    /** 分页查出来的就是 VO 本身时用这个。 */
    public static <T> PageVO<T> of(IPage<T> page) {
        return build(page, page.getRecords());
    }

    /** 分页查出来的是 entity，需要转成 VO 时用这个 —— 转换后 total 仍取原始分页的。 */
    public static <E, T> PageVO<T> of(IPage<E> page, Function<E, T> converter) {
        return build(page, page.getRecords().stream().map(converter).toList());
    }

    public static <T> PageVO<T> empty(long current, long size) {
        PageVO<T> vo = new PageVO<>();
        vo.records = List.of();
        vo.total = 0;
        vo.current = current;
        vo.size = size;
        vo.pages = 0;
        return vo;
    }

    private static <T> PageVO<T> build(IPage<?> page, List<T> records) {
        PageVO<T> vo = new PageVO<>();
        vo.records = records;
        vo.total = page.getTotal();
        vo.current = page.getCurrent();
        vo.size = page.getSize();
        vo.pages = page.getPages();
        return vo;
    }
}
