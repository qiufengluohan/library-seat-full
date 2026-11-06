<template>
  <el-container class="layout-container">
    <el-header class="header">
      <div class="header-left">
        <el-icon :size="24" color="#409EFF"><Reading /></el-icon>
        <span class="title">智慧图书馆管理后台</span>
      </div>

      <div class="header-right">
        <!-- 实时链路指示：WebSocket 断开时座位状态不再自动刷新 -->
        <el-tooltip
          :content="
            seatStore.wsConnected
              ? '已连接 /ws/seats，状态变化实时推送'
              : '实时连接已断开，正在每 3 秒重连'
          "
          placement="bottom"
        >
          <el-tag :type="seatStore.wsConnected ? 'success' : 'danger'" effect="dark" size="small">
            <span class="ws-dot" :class="{ live: seatStore.wsConnected }"></span>
            {{ seatStore.wsConnected ? '实时同步中' : '连接断开' }}
          </el-tag>
        </el-tooltip>

        <el-dropdown @command="handleCommand">
          <span class="user-info">
            <el-icon><User /></el-icon>
            <span>{{ authStore.displayName }}</span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>

    <el-container class="body-container">
      <el-aside width="200px" class="aside">
        <el-menu
          :default-active="activeMenu"
          router
          background-color="#304156"
          text-color="#bfcbd9"
          active-text-color="#409EFF"
        >
          <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.title }}</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useSeatStore } from '@/stores/seat'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const seatStore = useSeatStore()

// 与 router/index.js 的子路由一一对应（方案 §30 的六个页面，登录页不在此列）
const menus = [
  { path: '/dashboard', title: '实时大屏', icon: 'DataAnalysis' },
  { path: '/seats', title: '座位管理', icon: 'Grid' },
  { path: '/devices', title: '设备管理', icon: 'Cpu' },
  { path: '/rfid', title: 'RFID管理', icon: 'Postcard' },
  { path: '/violations', title: '违规与统计', icon: 'Warning' }
]

const activeMenu = computed(() => route.path)

onMounted(() => {
  // 全局只在这里建立一次 WebSocket，所有页面共用同一份座位状态
  seatStore.fetchSeats().catch(() => {})
  seatStore.connect()
})

onUnmounted(() => {
  seatStore.disconnect()
})

async function handleCommand(command) {
  if (command !== 'logout') return

  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }

  seatStore.reset()
  authStore.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}
</script>

<style scoped lang="scss">
.layout-container {
  height: 100vh;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background-color: #fff;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  padding: 0 20px;

  .header-left {
    display: flex;
    align-items: center;
    gap: 10px;

    .title {
      font-size: 18px;
      font-weight: bold;
      color: #303133;
    }
  }

  .header-right {
    display: flex;
    align-items: center;
    gap: 16px;

    .user-info {
      display: flex;
      align-items: center;
      gap: 5px;
      cursor: pointer;
      color: #606266;
      outline: none;

      &:hover {
        color: #409eff;
      }
    }
  }
}

.ws-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 5px;
  border-radius: 50%;
  background-color: #f56c6c;
  vertical-align: middle;

  &.live {
    background-color: #67c23a;
    animation: ws-pulse 1.6s infinite;
  }
}

@keyframes ws-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}

.body-container {
  height: calc(100vh - 60px);
}

.aside {
  background-color: #304156;
  overflow-x: hidden;

  .el-menu {
    border-right: none;
  }
}

.main {
  background-color: #f0f2f5;
  padding: 20px;
  overflow-y: auto;
}
</style>
