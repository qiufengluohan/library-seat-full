import { WS_MESSAGE_TYPE } from '@/constants/status'

const RECONNECT_DELAY = 3000

function resolveWsBase() {
  const configured = import.meta.env.VITE_WS_BASE_URL
  if (configured) return configured.replace(/\/$/, '')
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}`
}

/**
 * /ws/seats 客户端。
 *
 * 编码规范 §18：小程序与 Web 共用同一套消息格式，收到消息 -> 找 seat_id -> 更新该座位。
 * 编码规范 §38：断线 3 秒后重连，重连成功重新拉一次座位列表。不做指数退避。
 *
 * 全局只应存在一个实例，由 stores/seat.js 持有。
 */
class SeatWebSocket {
  constructor() {
    this.socket = null
    this.token = ''
    this.handlers = new Map()
    this.reconnectTimer = null
    this.manualClose = false
    this.connected = false
    this.hasConnectedOnce = false
  }

  connect(token) {
    this.token = token
    this.manualClose = false
    this._open()
  }

  close() {
    this.manualClose = true
    clearTimeout(this.reconnectTimer)
    this.reconnectTimer = null
    if (this.socket) {
      this.socket.onclose = null
      this.socket.onerror = null
      this.socket.onmessage = null
      this.socket.onopen = null
      this.socket.close()
      this.socket = null
    }
    this.connected = false
  }

  on(type, handler) {
    if (!this.handlers.has(type)) {
      this.handlers.set(type, new Set())
    }
    this.handlers.get(type).add(handler)
    return () => this.off(type, handler)
  }

  off(type, handler) {
    this.handlers.get(type)?.delete(handler)
  }

  _emit(type, payload) {
    this.handlers.get(type)?.forEach((handler) => {
      try {
        handler(payload)
      } catch (err) {
        console.error(`[ws] ${type} 处理失败`, err)
      }
    })
  }

  _open() {
    if (!this.token) {
      console.warn('[ws] 缺少 token，不建立连接')
      return
    }

    const url = `${resolveWsBase()}/ws/seats?token=${encodeURIComponent(this.token)}`

    try {
      this.socket = new WebSocket(url)
    } catch (err) {
      console.error('[ws] 创建连接失败', err)
      this._scheduleReconnect()
      return
    }

    this.socket.onopen = () => {
      this.connected = true
      const isReconnect = this.hasConnectedOnce
      this.hasConnectedOnce = true
      this._emit(isReconnect ? 'reconnected' : 'open', { timestamp: Date.now() })
    }

    this.socket.onmessage = (event) => this._handleMessage(event.data)

    this.socket.onerror = (event) => {
      console.warn('[ws] 连接错误', event)
      this._emit('error', event)
    }

    this.socket.onclose = () => {
      this.connected = false
      this._emit('close', { timestamp: Date.now() })
      this._scheduleReconnect()
    }
  }

  _handleMessage(raw) {
    let message
    try {
      message = JSON.parse(raw)
    } catch {
      console.warn('[ws] 收到非 JSON 消息，已忽略', raw)
      return
    }

    if (!message || !message.type) {
      console.warn('[ws] 消息缺少 type 字段，已忽略', message)
      return
    }

    // 分发给具体类型，同时给一个通配订阅方（便于调试面板）
    this._emit(message.type, message)
    this._emit('*', message)

    if (!Object.values(WS_MESSAGE_TYPE).includes(message.type)) {
      console.warn(`[ws] 未定义的消息类型: ${message.type}`)
    }
  }

  _scheduleReconnect() {
    if (this.manualClose || this.reconnectTimer) return
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      if (!this.manualClose) this._open()
    }, RECONNECT_DELAY)
  }
}

export const seatWebSocket = new SeatWebSocket()
export default seatWebSocket
