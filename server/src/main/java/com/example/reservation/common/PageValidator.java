package com.example.reservation.common;

/**
 * 分页参数校验（原 4 处重复实现收敛）
 * 判定条件与错误文案与既有实现逐字一致（ClassroomServiceImpl ×2、ReservationServiceImpl、UserServiceImpl）
 *
 * @author reservation-team
 */
public final class PageValidator {

    /** 每页条数上限（与原实现一致，勿改） */
    public static final long MAX_PAGE_SIZE = 500;

    private PageValidator() {
    }

    /**
     * 校验分页参数；不合法抛业务异常（页码 &lt;1 抛页码文案，每页条数超出 1-500 抛条数文案）。
     */
    public static void validate(long page, long size) {
        if (page < 1) {
            throw new BusinessException("页码必须大于等于 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException("每页条数必须在 1-500 之间");
        }
    }
}
