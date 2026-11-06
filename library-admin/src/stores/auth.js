import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { adminLogin } from '@/api/auth'
import { ADMIN_INFO_KEY, TOKEN_KEY, clearAuthStorage } from '@/utils/request'
import seatWebSocket from '@/utils/websocket'

function readStoredInfo() {
  try {
    return JSON.parse(localStorage.getItem(ADMIN_INFO_KEY) || '{}')
  } catch {
    return {}
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  // LoginVO: { token, user_id, nickname }（编码规范 §11.1）
  const adminInfo = ref(readStoredInfo())

  const isLoggedIn = computed(() => !!token.value)
  const displayName = computed(() => adminInfo.value.nickname || adminInfo.value.username || '管理员')

  async function login(username, password) {
    const data = await adminLogin({ username, password })

    token.value = data.token
    localStorage.setItem(TOKEN_KEY, data.token)

    adminInfo.value = {
      username,
      user_id: data.user_id,
      nickname: data.nickname
    }
    localStorage.setItem(ADMIN_INFO_KEY, JSON.stringify(adminInfo.value))

    return data
  }

  /**
   * 登出只做状态清理，跳转由调用方负责，避免 store <-> router 循环依赖。
   */
  function logout() {
    seatWebSocket.close()
    token.value = ''
    adminInfo.value = {}
    clearAuthStorage()
  }

  return {
    token,
    adminInfo,
    isLoggedIn,
    displayName,
    login,
    logout
  }
})
