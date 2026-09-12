package com.example.reservation.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.function.Function;

/**
 * 通用分页返回结构（spec.md 2.5：分页数据统一结构 { total, records }）
 *
 * @author reservation-team
 */
@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 总记录数 */
    private Long total;

    /** 当前页数据 */
    private List<T> records;

    public PageResult() {
    }

    public PageResult(Long total, List<T> records) {
        this.total = total;
        this.records = records;
    }

    /**
     * 从 MyBatis-Plus 分页对象转换（原样返回记录类型）
     */
    public static <E> PageResult<E> of(IPage<E> page) {
        return new PageResult<>(page.getTotal(), page.getRecords());
    }

    /**
     * 从 MyBatis-Plus 分页对象转换（记录类型映射，如实体 → VO 脱敏）
     */
    public static <E, V> PageResult<V> of(IPage<E> page, Function<E, V> mapper) {
        List<V> list = page.getRecords().stream().map(mapper).toList();
        return new PageResult<>(page.getTotal(), list);
    }
}
