package com.example.reservation.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 预约方案的「可选结束时间」档位（R15-2）。
 *
 * <p>用户只说开始时间、没说结束时间时，方案服务按 1 / 2 / 3 小时三档各检索一次，
 * 每档独立复核冲突，前端在方案卡里提供时段切换按钮。
 *
 * @author hyr
 */
@Data
public class AiPlanTimeOptionVO {

    /** 开始时间（HH:mm） */
    private String startTime;

    /** 结束时间（HH:mm） */
    private String endTime;

    /** 该时段可预约教室总数（含前 3 间之外的） */
    private int availableCount;

    /** 该时段因冲突/容量/类型被排除的教室数 */
    private int excludedCount;

    /** 该时段候选教室（最多 3 间；无候选时为空列表） */
    private List<AiPlanCandidateVO> candidates = new ArrayList<>();
}
