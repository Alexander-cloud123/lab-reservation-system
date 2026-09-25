<template>
  <!-- AI 智能推荐卡片（需求文档 2.4 教室列表页顶部「AI 为你推荐」卡片区） -->
  <el-card v-if="aiEnabled" shadow="never" class="ai-recommend-card">
    <template #header>
      <div class="ai-recommend-head">
        <span class="ai-icon"><el-icon :size="16"><MagicStick /></el-icon></span>
        <span class="ai-title">AI 为你推荐</span>
        <el-tag size="small" type="warning" effect="plain" round>AI 生成，仅供参考</el-tag>
      </div>
    </template>

    <el-skeleton v-if="loading" :rows="3" animated />

    <div v-else class="recommend-list">
      <div
        v-for="(item, index) in recommendations"
        :key="item.classroomId"
        class="recommend-item"
        v-clickable
        @click="goDetail(item)"
      >
        <span class="rank">{{ index + 1 }}</span>
        <div class="recommend-info">
          <div class="recommend-name">
            <span class="room-name">{{ item.name }}</span>
            <el-tag size="small" type="info" effect="plain" round>{{ typeText(item.type) }}</el-tag>
            <span class="room-meta">{{ item.building }} · {{ item.roomNo }} · 容量 {{ item.capacity }} 人</span>
          </div>
          <p class="recommend-reason">{{ item.reason }}</p>
        </div>
      </div>
      <el-empty v-if="!loading && !recommendations.length" description="暂无推荐教室" :image-size="60" />
    </div>

    <div class="ai-recommend-foot">
      <el-text type="info" size="small">基于您的历史预约习惯与实时空闲状态生成，点击卡片直达详情</el-text>
    </div>
  </el-card>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { MagicStick } from '@element-plus/icons-vue'
import { typeText } from '@/utils/dict'

/**
 * AI 智能推荐卡片（R7，需求文档 2.4 教室列表页「AI 为你推荐」卡片区）
 * 数据由父页面加载并传入（推荐接口只读，返回 enabled=false 时父页面隐藏整卡）
 * 提供「点击直达详情」入口；AI 结果不强制接受，仅作建议
 */
defineProps({
  /** AI 是否启用（false 时整卡隐藏） */
  aiEnabled: { type: Boolean, default: false },
  /** 推荐加载中 */
  loading: { type: Boolean, default: false },
  /** 推荐 Top3（含推荐理由） */
  recommendations: { type: Array, default: () => [] }
})

const router = useRouter()

/** 点击推荐教室直达详情页 */
function goDetail(item) {
  router.push(`/student/classrooms/${item.classroomId}`)
}
</script>

<style scoped>
.ai-recommend-card {
  margin-bottom: 16px;
  border-radius: var(--radius-lg);
  background: var(--bg-card);
  border: 1px solid var(--border-color-light);
}

.ai-recommend-card :deep(.el-card__header) {
  border-bottom: 1px solid var(--border-color-light);
}

.ai-recommend-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.ai-icon {
  width: 28px;
  height: 28px;
  border-radius: var(--radius-sm);
  background: var(--brand-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.ai-title {
  font-size: 14px;
  line-height: 22px;
  font-weight: 600;
  color: var(--text-primary);
}

.recommend-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.recommend-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--border-color-light);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.recommend-item:hover {
  border-color: var(--border-color);
  background: var(--bg-fill);
}

.rank {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  border-radius: var(--radius-sm);
  background: var(--brand-primary-light);
  color: var(--brand-primary);
  font-weight: 500;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}

.recommend-info {
  flex: 1;
  min-width: 0;
}

.recommend-name {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.room-name {
  font-size: 14px;
  line-height: 22px;
  font-weight: 500;
  color: var(--text-primary);
}

.room-meta {
  font-size: 12px;
  line-height: 20px;
  color: var(--text-regular);
}

.recommend-reason {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 20px;
  color: var(--text-regular);
}

.ai-recommend-foot {
  margin-top: 12px;
}
</style>
