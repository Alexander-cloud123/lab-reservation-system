package com.example.reservation.ai.support;

import com.example.reservation.ai.dto.AiRecommendItemVO;
import com.example.reservation.entity.Classroom;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 智能教室推荐——本地规则降级（spec.md 6.3 降级兜底必实现）
 * 大模型不可用或输出非法时，按「空闲度 > 楼栋偏好 > 类型偏好 > 容量匹配 > 历史活跃」规则打分排序 Top N，
 * 并为每间教室生成一句话推荐理由。
 *
 * 说明：本类由原 AiFallbackEngine 拆分而来（按接口职责一分为四，降低单类复杂度）。
 *
 * @author reservation-team
 */
@Component
public class AiRecommendFallback {

    /** 用户历史偏好（由推荐 Service 从预约记录 + 教室维度统计） */
    public record UserPreference(String preferredBuilding, Integer preferredType, String preferredTimeSlot, int avgCapacity) {
    }

    /** 推荐候选（教室 + 明日空闲特征） */
    public record RecommendCandidate(Classroom classroom, boolean fullyFree, int occupiedCount) {
    }

    /** 打分中间载体 */
    private record Scored(RecommendCandidate candidate, int score) {
    }

    /**
     * 规则打分排序：空闲度(10) > 楼栋偏好(5) > 类型偏好(4) > 容量匹配(3) > 历史活跃(1)
     * 返回按分数降序的 Top N（附一句话推荐理由）
     */
    public List<AiRecommendItemVO> rankAndTop(List<RecommendCandidate> candidates, UserPreference pref, int topCount) {
        // 注意：stream().toList() 返回不可变列表，必须先收集为可变列表再排序
        List<Scored> scored = candidates.stream().map(c -> score(c, pref)).collect(Collectors.toList());
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(c -> c.candidate().classroom().getId()));
        return scored.stream().limit(Math.max(topCount, 0)).map(s -> {
            AiRecommendItemVO item = new AiRecommendItemVO();
            Classroom c = s.candidate().classroom();
            item.setClassroomId(c.getId());
            item.setName(c.getName());
            item.setBuilding(c.getBuilding());
            item.setRoomNo(c.getRoomNo());
            item.setType(c.getType());
            item.setCapacity(c.getCapacity());
            item.setReason(buildReason(s, pref));
            return item;
        }).toList();
    }

    /** 单候选打分（空闲度 10 / 楼栋 5 / 类型 4 / 容量 3 / 活跃 1） */
    private Scored score(RecommendCandidate candidate, UserPreference pref) {
        Classroom c = candidate.classroom();
        int score = 0;
        // 空闲度：明日完全空闲优先（推荐核心维度）
        score += candidate.fullyFree() ? 10 : 5;
        // 楼栋偏好
        if (pref.preferredBuilding() != null && pref.preferredBuilding().equals(c.getBuilding())) {
            score += 5;
        }
        // 类型偏好
        if (pref.preferredType() != null && pref.preferredType().equals(c.getType())) {
            score += 4;
        }
        // 容量匹配：历史平均容量的 0.8~1.5 倍区间内
        if (pref.avgCapacity() > 0) {
            double lo = pref.avgCapacity() * 0.8;
            double hi = pref.avgCapacity() * 1.5;
            if (c.getCapacity() >= lo && c.getCapacity() <= hi) {
                score += 3;
            }
        }
        // 历史活跃：被约次数越多越优先（趋同偏好）
        score += Math.min(candidate.occupiedCount(), 5);
        return new Scored(candidate, score);
    }

    /** 生成一句话推荐理由（降级模式，按命中维度组合） */
    private String buildReason(Scored s, UserPreference pref) {
        Classroom c = s.candidate().classroom();
        List<String> parts = new ArrayList<>();
        parts.add(s.candidate().fullyFree() ? "明日全天空闲" : "明日仍有空闲时段");
        if (pref.preferredBuilding() != null && pref.preferredBuilding().equals(c.getBuilding())) {
            parts.add("您常约的" + c.getBuilding());
        }
        if (pref.preferredType() != null && pref.preferredType().equals(c.getType())) {
            parts.add("符合您常约的类型");
        }
        if (pref.avgCapacity() > 0 && c.getCapacity() >= pref.avgCapacity() * 0.8 && c.getCapacity() <= pref.avgCapacity() * 1.5) {
            parts.add("容量接近您的常用规模");
        }
        return String.join("，", parts);
    }
}