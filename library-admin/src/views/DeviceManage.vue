<template>
  <div class="device-manage">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div class="title-group">
            <span class="title">设备管理</span>
            <span class="subtitle">
              在线 {{ onlineCount }} / 共 {{ devices.length }}
            </span>
          </div>
          <div class="actions">
            <el-input
              v-model="keyword"
              placeholder="搜索设备ID或座位"
              clearable
              style="width: 200px"
            />
            <el-button :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="error"
        type="error"
        :closable="false"
        show-icon
        :title="error"
        style="margin-bottom: 12px"
      />

      <el-table :data="filteredDevices" border stripe v-loading="loading">
        <el-table-column prop="device_id" label="设备ID" width="140" />
        <el-table-column prop="seat_code" label="座位" width="120">
          <template #default="{ row }">{{ row.seat_code || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.online === true ? 'success' : 'info'" size="small">
              {{ row.online === true ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后上报" width="170">
          <template #default="{ row }">
            <el-tooltip :content="formatDateTime(row.last_report_at)" placement="top">
              <span>{{ relativeOf(row.last_report_at) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="pressure_adc" label="压力值(ADC)" width="120" align="right">
          <template #default="{ row }">{{ row.pressure_adc ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="红外(PIR)" width="100" align="center">
          <template #default="{ row }">
            <span v-if="row.pir_state === undefined || row.pir_state === null">-</span>
            <el-tag v-else :type="row.pir_state ? 'danger' : 'info'" size="small" effect="plain">
              {{ row.pir_state ? '有人' : '无人' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="告警" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.alarm_flag" type="danger" size="small">告警</el-tag>
            <span v-else class="muted">正常</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="230">
          <template #default="{ row }">
            <el-button size="small" @click="openConfig(row)">修改ADC阈值</el-button>
            <el-button size="small" type="warning" @click="openBuzzer(row)">
              测试蜂鸣器
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无设备数据" />
        </template>
      </el-table>
    </el-card>

    <!-- 修改 ADC 阈值：后端翻译成 OneNET 属性下发 adc_threshold -->
    <el-dialog v-model="configVisible" title="修改ADC阈值" width="440px">
      <el-form label-width="100px" @submit.prevent>
        <el-form-item label="设备ID">
          <span>{{ current?.device_id }}</span>
        </el-form-item>
        <el-form-item label="ADC阈值">
          <el-input-number v-model="adcThreshold" :min="0" :max="4095" :step="50" />
        </el-form-item>
      </el-form>
      <p class="tip">
        STM32 的 12 位 ADC 取值范围 0–4095。压力 ADC 超过该阈值判定为"有人坐下"，
        阈值需按实际座椅和 FSR402 分压电路现场标定。
      </p>
      <template #footer>
        <el-button @click="configVisible = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="submitConfig">下发</el-button>
      </template>
    </el-dialog>

    <!-- 测试蜂鸣器：后端翻译成 OneNET 服务调用 buzzer_ctrl -->
    <el-dialog v-model="buzzerVisible" title="测试蜂鸣器" width="440px">
      <el-form label-width="100px" @submit.prevent>
        <el-form-item label="设备ID">
          <span>{{ current?.device_id }}</span>
        </el-form-item>
        <el-form-item label="鸣响时长">
          <el-input-number v-model="durationMs" :min="100" :max="10000" :step="100" />
          <span class="unit">毫秒</span>
        </el-form-item>
      </el-form>
      <p class="tip">下发后设备端会立即鸣响，用于现场核对蜂鸣器接线与音量。</p>
      <template #footer>
        <el-button @click="buzzerVisible = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="submitBuzzer">下发</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getDevices, testBuzzer, updateDeviceConfig } from '@/api/device'
import { formatDateTime, formatRelative } from '@/utils/format'

const devices = ref([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')

const configVisible = ref(false)
const buzzerVisible = ref(false)
const current = ref(null)
const adcThreshold = ref(2000)
const durationMs = ref(1000)
const acting = ref(false)

// formatRelative 是相对当前时刻算的，加个心跳让"xx秒前"自己往前走
const tick = ref(0)
let timer = null

function relativeOf(value) {
  // 读一次 tick，把它变成渲染依赖；否则相对时间不会自动刷新
  void tick.value
  return formatRelative(value)
}

const filteredDevices = computed(() => {
  const word = keyword.value.trim().toLowerCase()
  if (!word) return devices.value
  return devices.value.filter((device) =>
    `${device.device_id ?? ''} ${device.seat_code ?? ''}`.toLowerCase().includes(word)
  )
})

const onlineCount = computed(
  () => devices.value.filter((device) => device.online === true).length
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    devices.value = (await getDevices()) ?? []
  } catch (e) {
    error.value = e.message || '加载设备失败'
  } finally {
    loading.value = false
  }
}

function openConfig(device) {
  current.value = device
  adcThreshold.value = 2000
  configVisible.value = true
}

function openBuzzer(device) {
  current.value = device
  durationMs.value = 1000
  buzzerVisible.value = true
}

async function submitConfig() {
  acting.value = true
  try {
    await updateDeviceConfig(current.value.device_id, adcThreshold.value)
    ElMessage.success('阈值已下发')
    configVisible.value = false
    await load()
  } catch (e) {
    if (e.silent) ElMessage.warning(e.message)
  } finally {
    acting.value = false
  }
}

async function submitBuzzer() {
  acting.value = true
  try {
    await testBuzzer(current.value.device_id, durationMs.value)
    ElMessage.success('蜂鸣器指令已下发')
    buzzerVisible.value = false
  } catch (e) {
    if (e.silent) ElMessage.warning(e.message)
  } finally {
    acting.value = false
  }
}

onMounted(() => {
  load()
  timer = setInterval(() => {
    tick.value += 1
  }, 30000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
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

.muted {
  color: #909399;
  font-size: 13px;
}

.tip {
  margin: 0;
  padding: 10px 12px;
  background: #f4f4f5;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.7;
  color: #909399;
}

.unit {
  margin-left: 8px;
  font-size: 13px;
  color: #909399;
}
</style>
