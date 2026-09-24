<template>
  <div class="seat-manage">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div class="title-group">
            <span class="title">座位管理</span>
            <span class="subtitle">共 {{ seatStore.total }} 个座位</span>
          </div>
          <div class="actions">
            <el-select
              v-model="filterArea"
              placeholder="全部区域"
              clearable
              style="width: 130px"
            >
              <el-option v-for="area in seatStore.areas" :key="area" :label="area" :value="area" />
            </el-select>
            <el-select
              v-model="filterStatus"
              placeholder="全部状态"
              clearable
              style="width: 130px"
            >
              <el-option
                v-for="(label, value) in SEAT_STATUS_LABEL"
                :key="value"
                :label="label"
                :value="Number(value)"
              />
            </el-select>
            <el-button :loading="seatStore.loading" @click="reload">刷新</el-button>
          </div>
        </div>
      </template>

      <!-- 方案 §32：颜色图例，红/黄/绿/灰 + 红色闪烁 -->
      <div class="legend">
        <span v-for="(label, value) in SEAT_STATUS_LABEL" :key="value" class="legend-item">
          <i class="dot" :style="{ background: seatStatusColor(Number(value)) }" />
          {{ label }}
        </span>
        <span class="legend-item">
          <i class="dot dot-offline" />
          离线角标
        </span>
      </div>

      <el-alert
        v-if="seatStore.loadError"
        type="error"
        :closable="false"
        show-icon
        :title="seatStore.loadError"
        style="margin-bottom: 12px"
      />

      <SeatGrid :seats="filteredSeats" @select="openDetail" />
    </el-card>

    <el-dialog v-model="detailVisible" title="座位详情" width="560px" destroy-on-close>
      <div v-loading="detailLoading" class="detail-body">
        <el-descriptions v-if="detail" :column="2" border size="small">
          <el-descriptions-item label="座位编号">{{ detail.seat_code || '-' }}</el-descriptions-item>
          <el-descriptions-item label="设备编号">{{ deviceCodeOf(detail.seat_id) }}</el-descriptions-item>
          <el-descriptions-item label="区域">{{ detail.area || '-' }}</el-descriptions-item>
          <el-descriptions-item label="楼层">{{ detail.floor ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="业务状态">
            <el-tag :type="seatStatusTag(detail.status)" size="small">
              {{ seatStatusLabel(detail.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="设备在线">
            <el-tag :type="detail.online === true ? 'success' : 'info'" size="small">
              {{ detail.online === true ? '在线' : '离线' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="学生">{{ detail.student_name || '无' }}</el-descriptions-item>
          <el-descriptions-item label="学习时长">
            {{ formatMinutes(detail.study_minutes) }}
          </el-descriptions-item>
          <el-descriptions-item label="预约时间">
            {{ formatDateTime(detail.reserve_time) }}
          </el-descriptions-item>
          <el-descriptions-item label="签到时间">
            {{ formatDateTime(detail.sign_time) }}
          </el-descriptions-item>
          <el-descriptions-item label="压力值(ADC)">
            {{ detail.pressure_adc ?? '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="红外(PIR)">
            {{ detail.pir_state === undefined ? '-' : detail.pir_state ? '有人' : '无人' }}
          </el-descriptions-item>
          <el-descriptions-item label="最后上报">
            {{ formatDateTime(detail.last_report_at) }}
          </el-descriptions-item>
          <el-descriptions-item label="告警标志">
            <el-tag v-if="detail.alarm" type="danger" size="small">告警中</el-tag>
            <span v-else class="muted">无</span>
          </el-descriptions-item>
        </el-descriptions>

        <!--
          压力值和红外只是设备原始数据，展示用。
          业务状态一律取后端给的 status，前端不做二次推断（方案 §5 / §53）。
        -->
      </div>

      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button
          type="warning"
          :disabled="!canClearAlarm"
          :loading="acting"
          @click="handleClearAlarm"
        >
          消除告警
        </el-button>
        <el-button
          type="danger"
          :disabled="!canForceRelease"
          :loading="acting"
          @click="handleForceRelease"
        >
          强制释放
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import SeatGrid from '@/components/SeatGrid.vue'
import { useSeatStore } from '@/stores/seat'
import { clearAlarm, forceRelease, getSeatDetail } from '@/api/seat'
import { getDevices } from '@/api/device'
import {
  SEAT_STATUS,
  SEAT_STATUS_LABEL,
  seatStatusColor,
  seatStatusLabel,
  seatStatusTag
} from '@/constants/status'
import { formatDateTime, formatMinutes } from '@/utils/format'

const seatStore = useSeatStore()

const filterArea = ref('')
const filterStatus = ref('')
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const acting = ref(false)

// SeatDetailVO 里没有 device_id，设备编号只能从 /admin/devices 的 seat_id 反查
const deviceCodeBySeatId = ref(new Map())

const filteredSeats = computed(() =>
  seatStore.seats.filter((seat) => {
    if (filterArea.value && seat.area !== filterArea.value) return false
    // 状态 0（空闲）是合法筛选值，不能当空值处理；clearable 清空后是 undefined
    if (filterStatus.value !== '' && filterStatus.value != null) {
      if (seat.status !== filterStatus.value) return false
    }
    return true
  })
)

const canClearAlarm = computed(
  () => detail.value?.alarm === true || detail.value?.status === SEAT_STATUS.ALARM
)

const canForceRelease = computed(
  () => detail.value != null && detail.value.status !== SEAT_STATUS.FREE
)

function deviceCodeOf(seatId) {
  return deviceCodeBySeatId.value.get(seatId) || '-'
}

async function loadDeviceCodes() {
  if (deviceCodeBySeatId.value.size) return
  try {
    const devices = await getDevices()
    const map = new Map()
    for (const device of devices ?? []) {
      map.set(device.seat_id, device.device_id)
    }
    deviceCodeBySeatId.value = map
  } catch {
    // 设备编号只是辅助信息，拿不到不影响座位详情的其余展示
  }
}

async function reload() {
  try {
    await seatStore.fetchSeats()
  } catch {
    // request.js 已提示
  }
}

async function openDetail(seat) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  loadDeviceCodes()
  try {
    detail.value = await getSeatDetail(seat.seat_id)
  } catch {
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail() {
  if (!detail.value) return
  try {
    detail.value = await getSeatDetail(detail.value.seat_id)
  } catch {
    // 保留旧数据，避免操作后详情框突然变空
  }
}

async function handleClearAlarm() {
  try {
    await ElMessageBox.confirm(
      `确定消除座位 ${detail.value.seat_code} 的告警吗？该操作会写入操作日志。`,
      '消除告警',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  acting.value = true
  try {
    await clearAlarm(detail.value.seat_id)
    ElMessage.success('告警已消除')
    await Promise.all([refreshDetail(), reload()])
  } catch (error) {
    if (error.silent) ElMessage.warning(error.message)
  } finally {
    acting.value = false
  }
}

async function handleForceRelease() {
  try {
    await ElMessageBox.confirm(
      `确定强制释放座位 ${detail.value.seat_code} 吗？当前预约会被结束，并记录一条学习记录。`,
      '强制释放',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  acting.value = true
  try {
    await forceRelease(detail.value.seat_id)
    ElMessage.success('座位已释放')
    await Promise.all([refreshDetail(), reload()])
  } catch (error) {
    if (error.silent) ElMessage.warning(error.message)
  } finally {
    acting.value = false
  }
}

onMounted(() => {
  if (seatStore.total === 0) reload()
})
</script>

<style scoped lang="scss">
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;

  .title-group {
    display: flex;
    align-items: baseline;
    gap: 10px;

    .title {
      font-size: 16px;
      font-weight: 700;
      color: #303133;
    }

    .subtitle {
      font-size: 13px;
      color: #909399;
    }
  }

  .actions {
    display: flex;
    gap: 10px;
  }
}

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  padding-bottom: 14px;
  margin-bottom: 14px;
  border-bottom: 1px solid #ebeef5;

  .legend-item {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    color: #606266;
  }

  .dot {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    display: inline-block;
  }

  .dot-offline {
    background: #fff;
    border: 1px solid #c0c4cc;
  }
}

.detail-body {
  min-height: 120px;

  .muted {
    color: #909399;
  }
}
</style>
