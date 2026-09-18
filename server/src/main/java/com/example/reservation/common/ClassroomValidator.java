package com.example.reservation.common;

import com.example.reservation.entity.Classroom;

/**
 * 教室存在性校验（原 9 处重复实现收敛；错误文案与既有实现逐字一致）
 *
 * @author reservation-team
 */
public final class ClassroomValidator {

    private ClassroomValidator() {
    }

    /** 校验教室存在，不存在抛业务异常（文案："教室不存在"） */
    public static void requireExists(Classroom classroom) {
        if (classroom == null) {
            throw new BusinessException("教室不存在");
        }
    }
}
