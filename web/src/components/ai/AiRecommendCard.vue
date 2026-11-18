<template>
  <!-- AI 智能推荐（需求文档 2.4 教室列表页顶部「AI 为你推荐」区）：排序结果，编号即推荐次序 -->
  <section v-if="aiEnabled" class="ai-recommend-card surface">
    <div class="ai-recommend-head">
      <h2 class="ai-title">AI 为你推荐</h2>
      <!-- 来源标注随降级状态切换：降级时结果来自本地规则，标注「AI 生成」不准确（AGENTS 4.4 标注口径） -->
      <span class="ai-recommend-source" :class="{ 'is-warning': message }">
        {{ message ? '本地规则结果' : 'AI 生成，仅供参考' }}
      </span>
    </div>

    <el-skeleton v-if="loading" :rows="3" animated />

    <div v-else class="recommend-list">
      <div
        v-for="(item, index) in recommendations"
        :key="item.classroomId"
        class="recommend-item"
        v-clickable
        @click="goDetail(item)"
      >
        <span class="recommend-rank">{{ String(index + 1).padStart(2, '0') }}</span>
        <div class="recommend-info">
          <div class="recommend-name">
            <span class="room-name">{{ item.name }}</span>
            <span class="room-type">{{ typeText(item.type) }}</span>
          </div>
          <p class="room-meta">{{ item.building }} {{ item.roomNo }}，容量 {{ item.capacity }} 人</p>
          <p class="recommend-reason">{{ item.reason }}</p>
        </div>
        <button type="button" class="recommend-go" @click.stop="goDetail(item)">查看</button>
      </div>
      <p v-if="!recommendations.length" class="recommend-empty">暂无推荐教室，可先用下方筛选条件自行查找。</p>
    </div>

    <!-- 降级时透出后端原因（限流/密钥缺失/服务异常/输出格式异常），使用户能区分真实模型与本地规则结果 -->
    <p class="foot-note" :class="{ 'is-warning': message }">
      {{ message || '基于你的历史预约习惯与实时空闲状态生成，点任一行可直达详情' }}
    </p>
  </section>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { typeText } from '@/utils/dict'

/**
 * AI 智能推荐（R7，需求文档 2.4 教室列表页「AI 为你推荐」区）
 * 数据由父页面加载并传入（推荐接口只读，返回 enabled=false 时父页面隐藏整块）
 * 提供「点击直达详情」入口；AI 结果不强制接受，仅作建议
 */
defineProps({
  /** AI 是否启用（false 时整块隐藏） */
  aiEnabled: { type: Boolean, default: false },
  /** 推荐加载中 */
  loading: { type: Boolean, default: false },
  /** 推荐 Top3（含推荐理由） */
  recommendations: { type: Array, default: () => [] },
  /** 后端降级提示（非空 = 当前为本地规则结果，由父页面从推荐接口透传） */
  message: { type: String, default: '' }
})

const router = useRouter()

/** 点击推荐教室直达详情页 */
function goDetail(item) {
  router.push(`/student/classrooms/${item.classroomId}`)
}
</script>

<style scoped>
.ai-recommend-card {
  padding: var(--space-xl) var(--space-xl) var(--space-lg);
  margin-bottom: var(--space-lg);
}

.ai-recommend-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-sm);
  padding-bottom: var(--space-sm);
}

.ai-title {
  margin: 0;
  font-size: var(--text-base);
  font-weight: 600;
  line-height: 1.4;
  color: var(--ink);
}

.ai-recommend-source {
  font-size: var(--text-xs);
  line-height: 1.5;
  color: var(--ink-soft);
}

.ai-recommend-source.is-warning {
  color: var(--warning);
}

.recommend-list {
  display: flex;
  flex-direction: column;
}

.recommend-item {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-md) 0;
  border-top: 1px solid var(--line);
  cursor: pointer;
}

.recommend-rank {
  flex: none;
  width: 22px;
  font-family: var(--font-num);
  font-size: var(--text-sm);
  font-variant-numeric: tabular-nums;
  color: var(--ink-soft);
}

.recommend-info {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.recommend-name {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--space-sm);
}

.room-name {
  font-size: var(--text-base);
  font-weight: 600;
  line-height: 1.4;
  color: var(--ink);
}

.room-type,
.room-meta {
  margin: 0;
  font-size: var(--text-xs);
  line-height: 1.6;
  color: var(--ink-soft);
}

.recommend-reason {
  margin: 0;
  font-size: var(--text-xs);
  line-height: 1.6;
  color: var(--ink-soft);
  overflow: hidden;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 1;
}

.recommend-go {
  flex: none;
  height: 30px;
  padding: 0 12px;
  border: 0;
  border-radius: var(--radius-control);
  background: transparent;
  color: var(--brand);
  font: inherit;
  font-size: var(--text-sm);
  font-weight: 500;
  cursor: pointer;
  transition: background-color 0.16s ease;
}

.recommend-go:hover {
  background: var(--brand-soft);
}

.recommend-go:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}

.recommend-empty {
  margin: 0;
  padding: var(--space-md) 0;
  border-top: 1px solid var(--line);
  font-size: var(--text-sm);
  color: var(--ink-soft);
}

.foot-note {
  margin: var(--space-sm) 0 0;
  padding-top: var(--space-sm);
  border-top: 1px solid var(--line);
  font-size: var(--text-xs);
  line-height: 1.6;
  color: var(--ink-soft);
}

.foot-note.is-warning {
  color: var(--warning);
}

/* ---------- 第四轮（晨光玻璃）覆盖 ---------- */
.ai-recommend-card {
  background: var(--glass-strong);
  border: 1px solid var(--glass-line);
}
.recommend-go {
  background-image: var(--grad);
  color: #fff;
  border: 0;
  border-radius: var(--radius-control);
  box-shadow: var(--shadow-brand);
  padding: 0 12px;
}
.recommend-go:hover {
  filter: brightness(1.06);
  color: #fff;
}
</style>