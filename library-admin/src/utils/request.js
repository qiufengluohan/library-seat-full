import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * JWT 存储键。auth store 与本模块共用这一个定义，避免两处写死字符串。
 */
export const TOKEN_KEY = 'library_admin_token'
export const ADMIN_INFO_KEY = 'library_admin_info'

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10000,
  headers: { 'Content-Type': 'application/json' }
})

function redirectToLogin() {
  clearAuthStorage()
  // 用整页跳转而不是 router.push：避免 request <-> router <-> store 循环依赖，
  // 同时把内存里的座位状态一并清掉。
  if (window.location.pathname !== '/login') {
    window.location.assign('/login')
  }
}

export function clearAuthStorage() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(ADMIN_INFO_KEY)
}

request.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 * 后端统一返回 Result{code, message, data}（编码规范 §12）。
 * 这里只把 data 交出去，非 200 一律转成 reject。
 */
request.interceptors.response.use(
  (response) => {
    const body = response.data

    // 非 Result 结构（例如导出流）直接放行
    if (body === null || typeof body !== 'object' || !('code' in body)) {
      return body
    }

    if (body.code === 200) {
      return body.data
    }

    if (body.code === 401) {
      redirectToLogin()
    }

    // 409 是预约/状态冲突，属于正常业务反馈，由调用方决定是否提示
    const error = new Error(body.message || '请求失败')
    error.code = body.code
    error.silent = body.code === 409
    if (!error.silent) {
      ElMessage.error(body.message || '请求失败')
    }
    return Promise.reject(error)
  },
  (error) => {
    const status = error.response?.status
    const body = error.response?.data
    const bodyMessage = body && typeof body === 'object' ? body.message : undefined

    if (status === 401) {
      redirectToLogin()
      return Promise.reject(error)
    }

    // Vite 代理连不上 library-seat 时返回空 body 的 500；
    // 真实后端的 500 一定带 Result body，据此区分两种情况。
    const unreachable = !error.response || (status >= 500 && !bodyMessage)

    const fallback = {
      400: '参数错误',
      403: '无权限访问',
      404: '请求的资源不存在',
      409: '业务冲突',
      500: '服务器错误'
    }[status]

    const text = unreachable
      ? '无法连接后端服务，请确认 library-seat 已启动'
      : bodyMessage || fallback || `请求失败(${status})`

    ElMessage.error(text)

    error.code = status
    // 覆盖 axios 原文，页面里的错误横幅才不会显示 "Request failed with status code 500"
    error.message = text
    return Promise.reject(error)
  }
)

export default request
