<template>
  <div class="dashboard">
    <div class="page-header">
      <h3>实时大屏</h3>
      <div class="header-meta">
        <span v-if="lastRefresh" class="meta-text">数据更新于 {{ formatTime(lastRefresh) }}</span>
        <el-tag v-if="loadError" type="danger" size="small">{{ loadError }}</el-tag>
        <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
      </div>
    </div>

    <!-- 座位状态（方案 §31） -->
    <el-row :gutter="16" class="stat-row">
      <el-col :xs="12" :sm="8" :md="6" :lg="3">
        <StatCard label="总座位" :value="dashboard.total_seats" icon="Grid" color="#409EFF" />
      </el-col>
      <el-col :xs="12" :sm="8" :md="6" :lg="3">
        <StatCard label="空闲" :value="dashboard.free_seats" icon="CircleCheck" color="#67C23A" />
      </el-col>
      <el-col :xs="12" :sm="8" :md="6" :lg="3">
        <StatCard label="已预约" :value="dashboard.reserved_seats" icon="Clock" color="#E6A23C" />
      </el-col>
      <el-col :xs="12" :sm="8" :md="6" :lg="3">
        <StatCard label="使用中" :value="dashboard.using_seats" icon="UserFilled" color="#F56C6C" />
      </el-col>
      <el-col :xs="12" :sm="8" :md="6" :lg="3">
        <StatCard label="暂离" :value="dashboard.away_seats" icon="Remove" color="#909399" />
      </el-col>
      <el-col :xs="12" :sm="8" :md="6" :lg="3">
        <StatCard label="异常告警" :value="dashboard.alarm_seats" icon="Warning" color="#F56C6C" />
      </el-col>
      <el-col :xs="12" :sm="8" :md="6" :lg="3">
        <StatCard label="设备在线" :value="dashboard.online_devices" icon="Link" color="#67C23A" />
      </el-col>
      <el-col :xs="12" :sm="8" :md="6" :lg="3">
        <StatCard label="设备离线" :value="dashboard.offline_devices" icon="Link" color="#909399" />
      </el-col>
    </el-row>

    <!-- 当天业务量 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :xs="12" :sm="6">
        <StatCard label="当天预约次数" :value="dashboard.today_reservations" icon="Tickets" color="#409EFF" />
      </el-col>
      <el-col :xs="12" :sm="6">
        <StatCard label="当天学习人数" :value="dashboard.today_users" icon="User" color="#67C23A" />
      </el-col>
      <el-col :xs="12" :sm="6">
        <StatCard
          label="当天学习总时长"
          :value="dashboard.today_study_minutes"
          suffix="分钟"
          icon="Timer"
          color="#E6A23C"
        />
      </el-col>
      <el-col :xs="12" :sm="6">
        <StatCard label="当天违规次数" :value="dashboard.today_violations" icon="Warning" color="#F56C6C" />
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :xs="24" :lg="12">
        <el-card shadow="hover">
          <template #header>座位状态分布</template>
          <div ref="barChartRef" class="chart"></div>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="12">
        <el-card shadow="hover">
          <template #header>各区域座位使用率</template>
          <div ref="lineChartRef" class="chart"></div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import StatCard from '@/components/StatCard.vue'
import { getDashboard } from '@/api/admin'
import { SEAT_STATUS, SEAT_STATUS_COLOR, SEAT_STATUS_LABEL } from '@/constants/status'
import { useSeatStore } from '@/stores/seat'
import { formatTime } from '@/utils/format'

const seatStore = useSeatStore()

const dashboard = ref({})
const loading = ref(false)
const loadError = ref('')
const lastRefresh = ref(null)

const barChartRef = ref(null)
const lineChartRef = ref(null)
let barChart = null
let lineChart = null
let refreshTimer = null

const statusOrder = [
  SEAT_STATUS.FREE,
  SEAT_STATUS.RESERVED,
  SEAT_STATUS.USING,
  SEAT_STATUS.AWAY,
  SEAT_STATUS.ALARM
]

const statusValues = computed(() => {
  const source = dashboard.value
  return statusOrder.map((status) => {
    const key = {
      [SEAT_STATUS.FREE]: 'free_seats',
      [SEAT_STATUS.RESERVED]: 'reserved_seats',
      [SEAT_STATUS.USING]: 'using_seats',
      [SEAT_STATUS.AWAY]: 'away_seats',
      [SEAT_STATUS.ALARM]: 'alarm_seats'
    }[status]
    return source[key] ?? 0
  })
})

/**
 * 各区域使用率：对已有座位做计数聚合。
 * 这里不推断任何业务状态，status 全部来自后端。
 *
 * 说明：方案的接口只有快照数据，没有时间序列，因此无法画真正的 24 小时趋势线。
 */
const areaUsage = computed(() => {
  const buckets = new Map()
  for (const seat of seatStore.seats) {
    const area = seat.area || '未分区'
    if (!buckets.has(area)) buckets.set(area, { total: 0, used: 0 })
    const bucket = buckets.get(area)
    bucket.total += 1
    if (seat.status !== SEAT_STATUS.FREE) bucket.used += 1
  }
  return [...buckets.entries()]
    .sort((a, b) => String(a[0]).localeCompare(String(b[0]), 'zh-CN'))
    .map(([area, { total, used }]) => ({
      area,
      rate: total === 0 ? 0 : Math.round((used / total) * 1000) / 10
    }))
})

async function refresh() {
  loading.value = true
  loadError.value = ''
  try {
    dashboard.value = (await getDashboard()) ?? {}
    lastRefresh.value = Date.now()
  } catch (error) {
    loadError.value = error.message || '加载大屏数据失败'
  } finally {
    loading.value = false
  }
}

function renderBarChart() {
  if (!barChart) return
  barChart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 30, bottom: 30 },
    xAxis: {
      type: 'category',
      data: statusOrder.map((status) => SEAT_STATUS_LABEL[status])
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        type: 'bar',
        barWidth: '48%',
        data: statusValues.value.map((value, index) => ({
          value,
          itemStyle: { color: SEAT_STATUS_COLOR[statusOrder[index]] }
        }))
      }
    ]
  })
}

function renderLineChart() {
  if (!lineChart) return
  lineChart.setOption({
    tooltip: { trigger: 'axis', valueFormatter: (value) => `${value}%` },
    grid: { left: 40, right: 20, top: 30, bottom: 30 },
    xAxis: { type: 'category', data: areaUsage.value.map((item) => item.area) },
    yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
    series: [
      {
        type: 'line',
        smooth: true,
        symbolSize: 7,
        areaStyle: { opacity: 0.12 },
        itemStyle: { color: '#409EFF' },
        data: areaUsage.value.map((item) => item.rate)
      }
    ]
  })
}

function handleResize() {
  barChart?.resize()
  lineChart?.resize()
}

// WebSocket 每改一次座位，store 的 lastUpdated 就会变；防抖后重拉一次大屏聚合值
watch(
  () => seatStore.lastUpdated,
  () => {
    clearTimeout(refreshTimer)
    refreshTimer = setTimeout(refresh, 1500)
  }
)

watch(statusValues, () => nextTick(renderBarChart))
watch(areaUsage, () => nextTick(renderLineChart), { deep: true })

onMounted(async () => {
  await nextTick()
  barChart = echarts.init(barChartRef.value)
  lineChart = echarts.init(lineChartRef.value)
  window.addEventListener('resize', handleResize)
  refresh()
})

onBeforeUnmount(() => {
  clearTimeout(refreshTimer)
  window.removeEventListener('resize', handleResize)
  barChart?.dispose()
  lineChart?.dispose()
  barChart = null
  lineChart = null
})
</script>

<style scoped lang="scss">
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;

  h3 {
    margin: 0;
    font-size: 18px;
    color: #303133;
  }

  .header-meta {
    display: flex;
    align-items: center;
    gap: 12px;

    .meta-text {
      font-size: 12px;
      color: #909399;
    }
  }
}

.stat-row {
  margin-bottom: 16px;

  .el-col {
    margin-bottom: 12px;
  }
}

.chart {
  height: 300px;
  width: 100%;
}
</style>
