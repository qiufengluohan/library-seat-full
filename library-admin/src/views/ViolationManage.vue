<template>
  <div class="violation-manage">
    <el-card shadow="never">
      <el-tabs v-model="activeTab">
        <!-- ==================== 违规记录（方案 §35：只做查询） ==================== -->
        <el-tab-pane label="违规记录" name="violations">
          <div class="toolbar">
            <el-select
              v-model="violationType"
              placeholder="全部类型"
              clearable
              style="width: 150px"
              @change="searchViolations"
            >
              <el-option
                v-for="(label, value) in VIOLATION_TYPE_LABEL"
                :key="value"
                :label="label"
                :value="value"
              />
            </el-select>
            <el-input
              v-model.number="violationSeatId"
              placeholder="按座位ID筛选"
              clearable
              style="width: 160px"
              @keyup.enter="searchViolations"
            />
            <el-button type="primary" @click="searchViolations">查询</el-button>
            <span class="toolbar-tip">共 {{ violationTotal }} 条</span>
          </div>

          <el-table :data="violations" border stripe v-loading="violationLoading">
            <el-table-column label="时间" width="180">
              <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
            </el-table-column>
            <el-table-column label="座位" width="120">
              <template #default="{ row }">{{ row.seat_code || `#${row.seat_id}` }}</template>
            </el-table-column>
            <el-table-column label="用户" width="160">
              <template #default="{ row }">
                {{ row.student_name || (row.user_id ? `用户#${row.user_id}` : '-') }}
              </template>
            </el-table-column>
            <el-table-column label="违规类型" width="120">
              <template #default="{ row }">
                <el-tag :type="VIOLATION_TYPE_TAG[row.type] ?? 'info'" size="small">
                  {{ violationTypeLabel(row.type) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="说明" min-width="240" show-overflow-tooltip>
              <template #default="{ row }">{{ row.description || '-' }}</template>
            </el-table-column>
            <template #empty>
              <el-empty description="暂无违规记录" />
            </template>
          </el-table>

          <el-pagination
            v-model:current-page="violationPage"
            v-model:page-size="violationSize"
            :total="violationTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            class="pager"
            @current-change="loadViolations"
            @size-change="searchViolations"
          />
        </el-tab-pane>

        <!-- ==================== 学习统计 ==================== -->
        <el-tab-pane label="学习统计" name="statistics">
          <div v-loading="statisticsLoading" class="stats-body">
            <el-row :gutter="16">
              <el-col :span="8">
                <StatCard
                  label="累计学习时长"
                  :value="formatMinutes(statistics.total_study_minutes)"
                  icon="Clock"
                  color="#409EFF"
                />
              </el-col>
              <el-col :span="8">
                <StatCard
                  label="累计学习次数"
                  :value="statistics.total_study_count ?? 0"
                  icon="Tickets"
                  color="#67C23A"
                  suffix="次"
                />
              </el-col>
              <el-col :span="8">
                <StatCard
                  label="平均每次时长"
                  :value="formatMinutes(statistics.average_study_minutes)"
                  icon="DataLine"
                  color="#E6A23C"
                />
              </el-col>
            </el-row>

            <p class="stats-note">
              统计口径来自 study_record 表：一条预约从签到到释放算一次学习，
              duration_minutes 由后端在释放时写入。强制释放同样会生成学习记录。
            </p>
          </div>
        </el-tab-pane>

        <!-- ==================== 操作日志（方案 §36） ==================== -->
        <el-tab-pane label="操作日志" name="logs">
          <div class="toolbar">
            <el-button :loading="logLoading" @click="loadLogs">刷新</el-button>
            <span class="toolbar-tip">共 {{ logTotal }} 条</span>
          </div>

          <el-table :data="logs" border stripe v-loading="logLoading">
            <el-table-column label="时间" width="180">
              <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
            </el-table-column>
            <el-table-column prop="admin_id" label="管理员ID" width="100" align="right" />
            <el-table-column label="操作" width="130">
              <template #default="{ row }">
                <el-tag :type="operationTag(row.operation)" size="small" effect="plain">
                  {{ row.operation }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="target" label="对象" width="160">
              <template #default="{ row }">{{ row.target || '-' }}</template>
            </el-table-column>
            <el-table-column prop="description" label="说明" min-width="240" show-overflow-tooltip>
              <template #default="{ row }">{{ row.description || '-' }}</template>
            </el-table-column>
            <template #empty>
              <el-empty description="暂无操作日志" />
            </template>
          </el-table>

          <el-pagination
            v-model:current-page="logPage"
            v-model:page-size="logSize"
            :total="logTotal"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            class="pager"
            @current-change="loadLogs"
            @size-change="searchLogs"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import StatCard from '@/components/StatCard.vue'
import { getLogs, getStatistics, getViolations } from '@/api/statistics'
import { VIOLATION_TYPE_LABEL, VIOLATION_TYPE_TAG, violationTypeLabel } from '@/constants/status'
import { formatDateTime, formatMinutes } from '@/utils/format'

const activeTab = ref('violations')

const violations = ref([])
const violationLoading = ref(false)
const violationType = ref('')
const violationSeatId = ref('')
const violationPage = ref(1)
const violationSize = ref(20)
const violationTotal = ref(0)

const statistics = ref({})
const statisticsLoading = ref(false)
let statisticsLoaded = false

const logs = ref([])
const logLoading = ref(false)
const logPage = ref(1)
const logSize = ref(20)
const logTotal = ref(0)
let logsLoaded = false

const OPERATION_TAG = {
  强制释放: 'danger',
  消除告警: 'warning',
  修改阈值: 'primary',
  测试蜂鸣器: 'warning',
  RFID绑定: 'success',
  RFID解绑: 'info'
}

function operationTag(operation) {
  return OPERATION_TAG[operation] ?? 'info'
}

/**
 * 分页接口既可能返回 MyBatis-Plus 的 IPage {records,total}，也可能直接返回数组。
 * 两种都接住，避免后端分页实现方式一变前端就白屏。
 */
function readPage(payload, fallbackTotal) {
  if (Array.isArray(payload)) return { list: payload, total: payload.length }
  if (payload && Array.isArray(payload.records)) {
    return { list: payload.records, total: payload.total ?? fallbackTotal }
  }
  return { list: [], total: 0 }
}

async function loadViolations() {
  violationLoading.value = true
  try {
    const params = { page: violationPage.value, size: violationSize.value }
    if (violationType.value) params.type = violationType.value
    if (violationSeatId.value) params.seat_id = violationSeatId.value

    const { list, total } = readPage(await getViolations(params), violationTotal.value)
    violations.value = list
    violationTotal.value = total
  } catch {
    // request.js 已提示
  } finally {
    violationLoading.value = false
  }
}

function searchViolations() {
  violationPage.value = 1
  loadViolations()
}

async function loadStatistics() {
  statisticsLoading.value = true
  try {
    statistics.value = (await getStatistics()) ?? {}
    statisticsLoaded = true
  } catch {
    // request.js 已提示
  } finally {
    statisticsLoading.value = false
  }
}

async function loadLogs() {
  logLoading.value = true
  try {
    const { list, total } = readPage(
      await getLogs({ page: logPage.value, size: logSize.value }),
      logTotal.value
    )
    logs.value = list
    logTotal.value = total
    logsLoaded = true
  } catch {
    // request.js 已提示
  } finally {
    logLoading.value = false
  }
}

function searchLogs() {
  logPage.value = 1
  loadLogs()
}

// 切到对应标签才拉数据，进页面不会一次性打三个接口
watch(activeTab, (tab) => {
  if (tab === 'statistics' && !statisticsLoaded) loadStatistics()
  if (tab === 'logs' && !logsLoaded) loadLogs()
})

onMounted(loadViolations)
</script>

<style scoped lang="scss">
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;

  .toolbar-tip {
    margin-left: auto;
    font-size: 13px;
    color: #909399;
  }
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}

.stats-body {
  min-height: 200px;
  padding-top: 6px;

  .stats-note {
    margin: 20px 0 0;
    padding: 12px 14px;
    background: #f4f4f5;
    border-radius: 4px;
    font-size: 13px;
    line-height: 1.8;
    color: #909399;
  }
}
</style>
