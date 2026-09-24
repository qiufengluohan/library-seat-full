/**
 * 数据访问层
 * ------------------------------------------------------------
 * 页面只依赖本文件，不直接调 utils/request.js。
 * 这里集中做三件事：
 *   1. 把后端接口路径映射成语义化的方法（对应 docs/04 的接口契约）；
 *   2. 把后端返回的字段（规范里统一是 snake_case）归一化成页面好用的结构；
 *   3. 提供座位状态 / 预约状态的文案与配色，避免页面里散落魔法值。
 *
 * 约定：
 *   · 小程序端不保存任何业务数据，一切以后端返回为准；
 *   · 后端没起或未配置地址时，所有方法都会 reject，由页面给出空态提示。
 */
const config = require('./config')
const http = require('./request')

/* ============================================================
 * 字段归一化
 * ==========================================================
 * 后端按规范使用 snake_case（seat_code / reserve_time / ...），
 * 这里统一兼容 snake_case 与 camelCase 两种写法，避免因序列化风格不同就取不到值。
 */
function firstDefined(obj, keys, fallback) {
  for (let i = 0; i < keys.length; i++) {
    const v = obj ? obj[keys[i]] : undefined
    if (v !== undefined && v !== null && v !== '') return v
  }
  return fallback
}

/** '2026-09-21T09:05:00' / '2026-09-21 09:05:00' → '2026-09-21 09:05' */
function normTime(t) {
  if (!t) return ''
  let s = String(t).replace('T', ' ')
  if (s.length > 16) s = s.slice(0, 16)
  return s
}

/** 'A4-203' → { row: 2, col: 3 }，仅用于座位图排版 */
function splitSeatCode(code) {
  const tail = String(code || '').split('-').pop().replace(/\D/g, '')
  if (!tail) return { row: 1, col: 1 }
  if (tail.length <= 2) return { row: 1, col: Number(tail) || 1 }
  return {
    row: Number(tail.slice(0, -2)) || 1,
    col: Number(tail.slice(-2)) || 1
  }
}

function isTrue(v) {
  return v === true || v === 1 || v === '1' || v === 'true'
}

/** 座位（seat 表 + seat_shadow 的 online / alarm 事实） */
function normSeat(s) {
  const seatCode = String(firstDefined(s, ['seat_code', 'seatCode'], ''))
  const pos = splitSeatCode(seatCode)
  const floor = Number(firstDefined(s, ['floor'], 0))
  const onlineRaw = firstDefined(s, ['online'], 1)
  return {
    id: firstDefined(s, ['seat_id', 'seatId', 'id'], 0),
    seatCode: seatCode,
    area: firstDefined(s, ['area', 'room_name', 'roomName'], ''),
    floor: floor,
    floorText: floor ? floor + '楼' : '',
    seatNo: seatCode ? seatCode.split('-').pop() : '',
    status: Number(firstDefined(s, ['status'], 0)),
    alarm: isTrue(firstDefined(s, ['alarm', 'alarm_flag', 'alarmFlag'], false)),
    /** online 缺失时按在线处理；0 / '0' / false 视为离线 */
    online: !(onlineRaw === false || onlineRaw === 0 || onlineRaw === '0'),
    row: pos.row,
    col: pos.col
  }
}

/** 预约（reservation 表 → ReservationVO） */
function normReservation(r) {
  if (!r) return null
  const seatCode = String(firstDefined(r, ['seat_code', 'seatCode'], ''))
  const floor = Number(firstDefined(r, ['floor'], 0))
  return {
    id: firstDefined(r, ['id'], 0),
    seatId: firstDefined(r, ['seat_id', 'seatId'], 0),
    seatCode: seatCode,
    seatNo: firstDefined(r, ['seat_no', 'seatNo'], seatCode ? seatCode.split('-').pop() : ''),
    area: firstDefined(r, ['area', 'room_name', 'roomName'], ''),
    floor: floor,
    floorText: floor ? floor + '楼' : '',
    status: firstDefined(r, ['status'], ''),
    reserveTime: normTime(firstDefined(r, ['reserve_time', 'reserveTime'], '')),
    signTime: normTime(firstDefined(r, ['sign_time', 'signTime'], '')),
    leaveTime: normTime(firstDefined(r, ['leave_time', 'leaveTime'], '')),
    returnTime: normTime(firstDefined(r, ['return_time', 'returnTime'], '')),
    releaseTime: normTime(firstDefined(r, ['release_time', 'releaseTime'], '')),
    reserveExpireAt: normTime(firstDefined(r, ['reserve_expire_at', 'reserveExpireAt'], '')),
    leaveExpireAt: normTime(firstDefined(r, ['leave_expire_at', 'leaveExpireAt'], ''))
  }
}

/** 用户（user 表 + rfid_user 表；没有的字段一律留空，不编造） */
function normUser(u) {
  const src = u || {}
  return {
    userId: firstDefined(src, ['user_id', 'userId', 'id'], ''),
    openid: firstDefined(src, ['openid'], ''),
    nickname: firstDefined(src, ['nickname'], ''),
    avatarUrl: firstDefined(src, ['avatar_url', 'avatarUrl'], ''),
    createdAt: normTime(firstDefined(src, ['created_at', 'createdAt'], '')),
    updatedAt: normTime(firstDefined(src, ['updated_at', 'updatedAt'], '')),
    /** rfid_user 表：未绑定时为空 */
    rfidUid: firstDefined(src, ['rfid_uid', 'rfidUid'], ''),
    rfidBindTime: normTime(firstDefined(src, ['rfid_bind_time', 'rfidBindTime', 'bind_time'], ''))
  }
}

/* ============================================================
 * 状态辅助
 * ========================================================== */
const SEAT_STATUS = {}
config.seatStatus.forEach(s => { SEAT_STATUS[s.code] = s })

function seatStatusLabel(code) {
  return (SEAT_STATUS[Number(code)] || SEAT_STATUS[0]).label
}

function seatStatusKey(code) {
  return (SEAT_STATUS[Number(code)] || SEAT_STATUS[0]).key
}

function seatStatusColor(code) {
  return (SEAT_STATUS[Number(code)] || SEAT_STATUS[0]).color
}

const RESERVE_STATUS = {}
config.reservationStatus.forEach(s => { RESERVE_STATUS[s.key] = s })

function reserveStatusLabel(key) {
  return (RESERVE_STATUS[key] || {}).label || ''
}

function reserveStatusTag(key) {
  return (RESERVE_STATUS[key] || {}).tag || 'tag-grey'
}

/* ============================================================
 * 接口
 * ========================================================== */

/** 是否已配置后端地址 */
function isConfigured() {
  return http.isConfigured()
}

/** 未配置后端时的统一错误，页面据此显示空态 */
function notConfiguredError() {
  const e = new Error('尚未配置后端地址')
  e.kind = 'unconfigured'
  return e
}

/** 微信登录：wx.login 的 code 换 token（POST /api/auth/wechat-login） */
function wechatLogin(code) {
  return http.post(config.api.wechatLogin, { code: code }).then(res => {
    const d = res || {}
    if (d.token) http.setToken(d.token)
    return {
      token: d.token || '',
      user: normUser({
        user_id: d.user_id,
        openid: d.openid,
        nickname: d.nickname,
        avatar_url: d.avatar_url,
        created_at: d.created_at,
        updated_at: d.updated_at,
        rfid_uid: d.rfid_uid,
        rfid_bind_time: d.rfid_bind_time
      })
    }
  })
}

/** 我的资料（GET /api/users/me） */
function getUserProfile() {
  return http.get(config.api.userMe).then(normUser)
}

/** 保存资料（PUT /api/users/me）——只能改昵称与头像 */
function updateUserProfile(p) {
  const body = {}
  if (p && typeof p.nickname !== 'undefined') body.nickname = p.nickname
  if (p && typeof p.avatarUrl !== 'undefined') body.avatar_url = p.avatarUrl
  return http.put(config.api.userMe, body).then(normUser)
}

/** 全部座位（GET /api/seats） */
function getSeats() {
  return http.get(config.api.seats).then(list => (list || []).map(normSeat))
}

/** 由座位列表按「楼层 + 阅览室」归并出阅览室，阅览室不做单独表 */
function groupRooms(seats) {
  const map = {}
  const order = []
  ;(seats || []).forEach(s => {
    const key = s.floor + '|' + s.area
    if (!map[key]) {
      map[key] = {
        key: key,
        name: s.area,
        floor: s.floorText,
        floorNo: s.floor,
        totalSeats: 0,
        rows: 1,
        cols: 1
      }
      order.push(key)
    }
    const room = map[key]
    room.totalSeats++
    if (s.row > room.rows) room.rows = s.row
    if (s.col > room.cols) room.cols = s.col
  })
  order.sort()
  return order.map(k => map[k])
}

/** 阅览室列表（由座位表归并而来） */
function getRooms() {
  return getSeats().then(groupRooms)
}

/** 首页总览：阅览室 + 全部座位 + 全馆统计（一次请求拿全） */
function getOverview() {
  return getSeats().then(seats => ({
    rooms: groupRooms(seats),
    seats: seats,
    stats: countStats(seats)
  }))
}

/**
 * 座位图（GET /api/seats 后按阅览室筛选并排版）
 * @param {string} roomKey 阅览室 key；不传则取第一个
 */
function getSeatMap(roomKey) {
  return getSeats().then(seats => {
    const rooms = groupRooms(seats)
    const room = rooms.filter(r => r.key === roomKey)[0] || rooms[0] || null
    if (!room) {
      return { room: null, layout: { rows: 0, cols: 0 }, seats: [], stats: emptyStats() }
    }
    const list = seats.filter(s => s.floor === room.floorNo && s.area === room.name)
    return {
      room: room,
      layout: { rows: room.rows, cols: room.cols },
      seats: list,
      stats: countStats(list),
      rooms: rooms
    }
  })
}

function emptyStats() {
  return { total: 0, free: 0, reserved: 0, using: 0, away: 0, alarm: 0, offline: 0 }
}

function countStats(list) {
  const stats = emptyStats()
  ;(list || []).forEach(s => {
    stats.total++
    if (s.status === 0) stats.free++
    else if (s.status === 1) stats.reserved++
    else if (s.status === 2) stats.using++
    else if (s.status === 3) stats.away++
    else if (s.status === 4) stats.alarm++
    if (s.online === false) stats.offline++
  })
  return stats
}

/* ---------- 预约 ---------- */

/** 创建预约（POST /api/reservations，只入参 seat_id） */
function createReservation(seatId) {
  return http.post(config.api.reservations, { seat_id: seatId }).then(normReservation)
}

/** 当前进行中的预约（GET /api/reservations/current），没有则返回 null */
function getCurrentReservation() {
  return http.get(config.api.reservationCurrent).then(r => (r ? normReservation(r) : null))
}

/**
 * 预约列表（GET /api/reservations?status=）
 * @param {string} status ALL | RESERVED | USING | AWAY | FINISHED
 *        FINISHED = COMPLETED + CANCELLED + TIMEOUT，由后端归并
 */
function getReservations(status) {
  const q = status && status !== 'ALL' ? ('?status=' + encodeURIComponent(status)) : ''
  return http.get(config.api.reservations + q).then(list => (list || []).map(normReservation))
}

/** 预约详情（GET /api/reservations/{id}） */
function getReservationDetail(id) {
  return http.get(config.api.reservations + '/' + id).then(normReservation)
}

/** 预约动作：cancel / leave / return / release（POST /api/reservations/{id}/{action}） */
function reservationAction(id, action) {
  return http.post(config.api.reservations + '/' + id + '/' + action).then(normReservation)
}

module.exports = {
  /* 基础设施 */
  isConfigured: isConfigured,
  notConfiguredError: notConfiguredError,
  getToken: http.getToken,
  clearToken: http.clearToken,

  /* 状态辅助 */
  seatStatusLabel: seatStatusLabel,
  seatStatusKey: seatStatusKey,
  seatStatusColor: seatStatusColor,
  reserveStatusLabel: reserveStatusLabel,
  reserveStatusTag: reserveStatusTag,

  /* 工具（页面统一用 api 里的时间格式化，避免各页各写一份） */
  normTime: normTime,

  /* 登录与用户 */
  wechatLogin: wechatLogin,
  getUserProfile: getUserProfile,
  updateUserProfile: updateUserProfile,

  /* 座位 */
  getSeats: getSeats,
  getRooms: getRooms,
  getOverview: getOverview,
  getSeatMap: getSeatMap,

  /* 预约 */
  createReservation: createReservation,
  getCurrentReservation: getCurrentReservation,
  getReservations: getReservations,
  getReservationDetail: getReservationDetail,
  cancelReservation: id => reservationAction(id, 'cancel'),
  leaveSeat: id => reservationAction(id, 'leave'),
  returnSeat: id => reservationAction(id, 'return'),
  releaseSeat: id => reservationAction(id, 'release')
}
