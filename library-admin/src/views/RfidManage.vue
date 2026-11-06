<template>
  <div class="rfid-manage">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <div class="title-group">
            <span class="title">RFID管理</span>
            <span class="subtitle">共 {{ binds.length }} 条绑定</span>
          </div>
          <div class="actions">
            <el-input
              v-model="keyword"
              placeholder="搜索UID或学生"
              clearable
              style="width: 200px"
            />
            <el-button type="primary" @click="openBind">绑定</el-button>
            <el-button :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="RFID 只证明「这个人来签到了」，不决定坐哪个座位。座位由学生当前那条 RESERVED 预约决定。"
        style="margin-bottom: 12px"
      />

      <el-alert
        v-if="error"
        type="error"
        :closable="false"
        show-icon
        :title="error"
        style="margin-bottom: 12px"
      />

      <el-table :data="filteredBinds" border stripe v-loading="loading">
        <el-table-column label="学生" min-width="140">
          <template #default="{ row }">
            {{ row.student_name || `用户#${row.user_id}` }}
          </template>
        </el-table-column>
        <el-table-column prop="user_id" label="用户ID" width="100" align="right" />
        <el-table-column prop="rfid_uid" label="RFID UID" min-width="180">
          <template #default="{ row }">
            <span class="mono">{{ row.rfid_uid }}</span>
          </template>
        </el-table-column>
        <el-table-column label="绑定时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.bind_time) }}</template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="100">
          <template #default="{ row }">
            <el-button size="small" type="danger" @click="handleUnbind(row)">解绑</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无绑定记录" />
        </template>
      </el-table>
    </el-card>

    <el-dialog v-model="bindVisible" title="绑定RFID" width="460px">
      <el-form :model="form" label-width="100px" @submit.prevent>
        <el-form-item label="RFID UID" required>
          <el-input
            v-model="form.rfidUid"
            placeholder="如 A1B2C3D4"
            maxlength="64"
            clearable
          />
        </el-form-item>
        <el-form-item label="用户ID" required>
          <el-input-number v-model="form.userId" :min="1" :controls="false" style="width: 100%" />
        </el-form-item>
      </el-form>
      <p class="tip">
        UID 为学生卡在共享读卡器上刷出的十六进制编号。用户ID 是 user 表主键，
        可在座位详情的"当前用户"或违规记录里查到——本方案没有提供学生检索接口。
      </p>
      <template #footer>
        <el-button @click="bindVisible = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="submitBind">确定绑定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { bindRfid, getRfidBinds, unbindRfid } from '@/api/rfid'
import { formatDateTime } from '@/utils/format'

const binds = ref([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')

const bindVisible = ref(false)
const acting = ref(false)
const form = reactive({ rfidUid: '', userId: undefined })

const filteredBinds = computed(() => {
  const word = keyword.value.trim().toLowerCase()
  if (!word) return binds.value
  return binds.value.filter((bind) =>
    `${bind.rfid_uid ?? ''} ${bind.student_name ?? ''} ${bind.user_id ?? ''}`
      .toLowerCase()
      .includes(word)
  )
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    binds.value = (await getRfidBinds()) ?? []
  } catch (e) {
    error.value = e.message || '加载绑定记录失败'
  } finally {
    loading.value = false
  }
}

function openBind() {
  form.rfidUid = ''
  form.userId = undefined
  bindVisible.value = true
}

async function submitBind() {
  const uid = form.rfidUid.trim()
  if (!uid) {
    ElMessage.warning('请输入RFID UID')
    return
  }
  if (!form.userId) {
    ElMessage.warning('请输入用户ID')
    return
  }

  acting.value = true
  try {
    await bindRfid(uid, form.userId)
    ElMessage.success('绑定成功')
    bindVisible.value = false
    await load()
  } catch (e) {
    // UID 或 user_id 都是唯一键，重复绑定后端返回 409
    if (e.silent) ElMessage.warning(e.message)
  } finally {
    acting.value = false
  }
}

async function handleUnbind(row) {
  try {
    await ElMessageBox.confirm(
      `确定解绑 ${row.student_name || `用户#${row.user_id}`} 的卡片 ${row.rfid_uid} 吗？解绑后该卡刷卡将不再触发签到。`,
      '解绑RFID',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  try {
    await unbindRfid(row.rfid_uid)
    ElMessage.success('已解绑')
    await load()
  } catch (e) {
    if (e.silent) ElMessage.warning(e.message)
  }
}

onMounted(load)
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

.mono {
  font-family: 'Consolas', 'Monaco', monospace;
  letter-spacing: 0.5px;
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
</style>
