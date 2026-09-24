/**
 * 座位实时推送客户端（Spring Boot /ws/seats）
 * ------------------------------------------------------------
 * 规范 §46 要求"WebSocket 实现小程序和管理端实时同步"，本文件就是小程序侧的那一半。
 *
 * 只维持**一条**连接：一个小程序同时最多 5 个 socket，而全馆状态是广播给所有人的，
 * 多开只会重复消费。页面通过 subscribe() 挂监听，不自己碰 wx.connectSocket。
 *
 * 与 utils/request.js 的分工：HTTP 走 request.js，socket 走这里，
 * 两者都只认 config.server.baseUrl —— 换后端地址永远只改那一行。
 *
 * 消息（后端 WsMessage，字段 snake_case，未赋值的字段后端根本不发）：
 *   { type:'SEAT_UPDATE',   seat_id, status, alarm, online, timestamp }
 *   { type:'DEVICE_STATUS', seat_id, online, timestamp }
 *   { type:'ALARM',         seat_id, alarm, timestamp }
 * type 与后端 WebSocketService 的广播点是穷举的，新增类型时这里不用改 ——
 * 本文件不判断类型语义，原样转给页面。
 *
 * ⚠️ 一条诚实的说明：后端不主动发心跳，所以**半开的连接检测不到**
 * （对端断了但没收到 close 帧）。真出现这种情况的表现是"页面不再自动刷新"，
 * 而不是"显示错误数据" —— 页面 onShow 和下拉刷新仍然会拉一次全量接口，
 * 数据库才是事实来源。要做真正的双向心跳得后端配合，超出 §18 的报文定义。
 */
const config = require('./config')
const http = require('./request')

/** 连续失败这么多次就停手，等下一次 ensure()（通常是页面 onShow）再试 */
const MAX_CONSECUTIVE_FAILURES = 5

let socketTask = null
let connected = false
let connecting = false
let closedByUs = false
let failures = 0
let retryTimer = null

/** type 常量，页面里不想写字符串时用 */
const MESSAGE_TYPES = {
  SEAT_UPDATE: 'SEAT_UPDATE',
  DEVICE_STATUS: 'DEVICE_STATUS',
  ALARM: 'ALARM'
}

/* ============================================================
 * 订阅
 * ========================================================== */
const handlers = new Set()

/**
 * @param {(msg: {type: string, seatId: number, status: number, alarm: boolean, online: boolean, timestamp: number, raw: object}) => void} handler
 * @return {() => void} 取消订阅。页面记得在 onUnload 里调，否则页面销毁后
 *                       回调还会打进来，报 "setData on destroyed page"
 */
function subscribe(handler) {
  if (typeof handler === 'function') handlers.add(handler)
  ensure()
  return function unsubscribe() {
    handlers.delete(handler)
  }
}

function dispatch(payload) {
  let msg = payload
  if (typeof msg === 'string') {
    try {
      msg = JSON.parse(msg)
    } catch (e) {
      // 平台代理偶尔会插一句非 JSON 的握手文本，丢掉就行，不值得弹提示
      console.warn('[ws] 收到无法解析的消息，已忽略')
      return
    }
  }
  if (!msg || typeof msg !== 'object') return

  // 后端全局是 snake_case，这里转成页面好用的驼峰，同时保留 raw
  const normalized = {
    type: msg.type || '',
    seatId: msg.seat_id !== undefined ? msg.seat_id : msg.seatId,
    status: msg.status,
    alarm: msg.alarm,
    online: msg.online,
    timestamp: msg.timestamp,
    raw: msg
  }
  handlers.forEach(h => {
    try {
      h(normalized)
    } catch (e) {
      // 一个页面的回调抛错不能影响其他订阅者，更不能把 socket 带崩
      console.error('[ws] 订阅回调抛错:', e)
    }
  })
}

/* ============================================================
 * 连接管理
 * ========================================================== */

/** 由 baseUrl 推导 socket 地址；未配置后端或未登录时返回空串 */
function socketUrl() {
  const base = http.baseUrl()
  const token = http.getToken()
  if (!base || !token) return ''
  const path = (config.server && config.server.wsPath) || '/ws/seats'
  // http:// → ws://   https:// → wss://
  return base.replace(/^http/, 'ws') + path + '?token=' + encodeURIComponent(token)
}

function reconnectDelay() {
  const min = (config.server && config.server.wsReconnectMinMs) || 2000
  const max = (config.server && config.server.wsReconnectMaxMs) || 20000
  return Math.min(failures ? min * Math.pow(2, Math.min(failures - 1, 5)) : min, max)
}

function clearRetryTimer() {
  if (retryTimer) {
    clearTimeout(retryTimer)
    retryTimer = null
  }
}

function scheduleRetry() {
  clearRetryTimer()
  if (closedByUs) return
  failures++
  if (failures > MAX_CONSECUTIVE_FAILURES) {
    console.warn('[ws] 连续 ' + MAX_CONSECUTIVE_FAILURES + ' 次连不上，暂停重连（下次进页面会再试）')
    return
  }
  const delay = reconnectDelay()
  retryTimer = setTimeout(open, delay)
}

/**
 * 幂等地保证"该连就连上"。
 * 页面 onShow、登录成功后都可以直接调，不用先判断状态。
 *
 * @return {boolean} 是否已连上或正在连
 */
function ensure() {
  if (connected || connecting) return true
  if (!socketUrl()) return false
  // 之前放弃过重连，这次进页面等于给用户一个新的机会，计数清零
  if (failures > MAX_CONSECUTIVE_FAILURES) failures = 0
  clearRetryTimer()
  open()
  return true
}

function open() {
  const url = socketUrl()
  if (!url || connected || connecting) return
  connecting = true
  closedByUs = false

  let task
  try {
    task = wx.connectSocket({ url, timeout: 8000 })
  } catch (e) {
    connecting = false
    console.error('[ws] 发起连接就失败了:', e)
    scheduleRetry()
    return
  }
  socketTask = task

  task.onOpen(() => {
    connecting = false
    connected = true
    failures = 0
  })

  task.onMessage(res => dispatch(res && res.data))

  task.onClose(() => {
    connected = false
    socketTask = null
    if (!closedByUs) scheduleRetry()
  })

  // 握手被后端拒了（token 无效 → 403）也会走到这里，随后 onClose 负责重连或放弃
  task.onError(err => {
    connecting = false
    console.warn('[ws] 连接出错:', (err && (err.errMsg || err.message)) || err)
  })
}

/**
 * 主动关闭。退出登录时必须调一次，否则上一个用户的 token 还会挂在 socket 上，
 * 后端按连接建立时的 token 认人，换账号后会继续收到旧会话的广播。
 */
function close() {
  closedByUs = true
  failures = 0
  clearRetryTimer()
  const task = socketTask
  connected = false
  connecting = false
  socketTask = null
  if (!task) return
  try {
    task.close({ code: 1000, reason: 'client close' })
  } catch (e) {
    // 已经断了的 task 调 close 会抛，无所谓
  }
}

function isConnected() {
  return connected
}

module.exports = {
  ensure: ensure,
  close: close,
  subscribe: subscribe,
  isConnected: isConnected,
  socketUrl: socketUrl,
  MESSAGE_TYPES: MESSAGE_TYPES
}
