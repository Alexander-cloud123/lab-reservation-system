<template>
  <details class="dashboard-chart-table">
    <summary>查看数据表</summary>
    <div class="dashboard-chart-table__scroll">
      <table>
        <caption>{{ caption }}</caption>
        <thead>
          <tr>
            <th v-for="column in columns" :key="column.key" scope="col">
              {{ column.label }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!rows.length">
            <td class="dashboard-chart-table__empty" :colspan="columns.length || 1">暂无数据</td>
          </tr>
          <template v-else>
            <tr v-for="(row, rowIndex) in rows" :key="rowIndex">
              <td v-for="column in columns" :key="column.key">
                {{ row[column.key] ?? '—' }}
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
  </details>
</template>

<script setup>
defineOptions({ name: 'DashboardChartTable' })

defineProps({
  caption: {
    type: String,
    required: true
  },
  columns: {
    type: Array,
    default: () => []
  },
  rows: {
    type: Array,
    default: () => []
  }
})
</script>

<style scoped>
.dashboard-chart-table {
  margin-top: var(--space-lg);
  font-size: var(--text-sm);
}

.dashboard-chart-table summary {
  width: fit-content;
  padding: var(--space-xs) 0;
  color: var(--brand);
  font-weight: 500;
  cursor: pointer;
}

.dashboard-chart-table summary:hover {
  color: var(--brand-deep);
}

.dashboard-chart-table summary:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 4px;
  border-radius: var(--space-xs);
}

.dashboard-chart-table__scroll {
  margin-top: var(--space-sm);
  overflow-x: auto;
}

.dashboard-chart-table table {
  width: 100%;
  min-width: 360px;
  border-collapse: collapse;
  font-variant-numeric: tabular-nums;
}

.dashboard-chart-table caption {
  margin-bottom: var(--space-sm);
  color: var(--ink-soft);
  text-align: left;
  caption-side: top;
}

.dashboard-chart-table th,
.dashboard-chart-table td {
  padding: var(--space-sm) var(--space-md);
  border-bottom: 1px solid var(--line);
  line-height: 1.5;
  text-align: left;
  white-space: nowrap;
}

.dashboard-chart-table th {
  background: var(--bg-subtle);
  color: var(--ink-soft);
  font-weight: 600;
}

.dashboard-chart-table td {
  color: var(--ink);
}

.dashboard-chart-table tbody tr:last-child td {
  border-bottom: 0;
}

.dashboard-chart-table td.dashboard-chart-table__empty {
  color: var(--ink-mute);
  text-align: center;
}
</style>
