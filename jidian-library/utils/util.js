/**
 * 通用工具方法
 */
const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

/**
 * 格式化日期：formatDate(new Date(), 'YYYY-MM-DD')
 * 兼容三种入参：Date 对象 / 'YYYY-MM-DD'、'YYYY-MM-DD HH:mm' 等字符串 / 时间戳（毫秒）
 */
function formatDate(date, fmt) {
  let d
  if (typeof date === 'string') d = new Date(date.replace(/-/g, '/'))
  else if (typeof date === 'number') d = new Date(date)
  else d = date || new Date()
  if (!(d instanceof Date) || isNaN(d.getTime())) d = new Date()
  const o = {
    YYYY: d.getFullYear(),
    MM: pad(d.getMonth() + 1),
    DD: pad(d.getDate()),
    HH: pad(d.getHours()),
    mm: pad(d.getMinutes()),
    ss: pad(d.getSeconds())
  }
  return (fmt || 'YYYY-MM-DD').replace(/YYYY|MM|DD|HH|mm|ss/g, k => o[k])
}

/** 今天 YYYY-MM-DD */
function today() {
  return formatDate(new Date(), 'YYYY-MM-DD')
}

/** 星期中文 */
function weekdayCN(dateStr) {
  const d = new Date((dateStr || today()).replace(/-/g, '/'))
  return WEEK[d.getDay()]
}

/**
 * 生成未来 n 天的日期选项（用于横向日期选择器）
 * @returns [{ date, day, week, isToday, disabled }]
 */
function dateOptions(n, startOffset) {
  const list = []
  const base = new Date()
  for (let i = startOffset || 0; i < (startOffset || 0) + n; i++) {
    const d = new Date(base.getTime() + i * 86400000)
    list.push({
      date: formatDate(d, 'YYYY-MM-DD'),
      day: d.getDate(),
      month: d.getMonth() + 1,
      week: WEEK[d.getDay()],
      isToday: i === 0
    })
  }
  return list
}

/** 'HH:mm' -> 分钟数 */
function toMinutes(t) {
  if (!t) return 0
  const a = String(t).split(':')
  return parseInt(a[0], 10) * 60 + parseInt(a[1], 10)
}

/** 分钟数 -> 'HH:mm' */
function toTime(m) {
  return pad(Math.floor(m / 60)) + ':' + pad(m % 60)
}

/** 时长文案：'2时30分' */
function durationText(start, end) {
  const diff = Math.max(0, toMinutes(end) - toMinutes(start))
  const h = Math.floor(diff / 60)
  const m = diff % 60
  if (h && m) return h + '时' + m + '分'
  if (h) return h + '时'
  return m + '分'
}

/** 分钟 -> '2h30m'，用于统计展示 */
function minutesToHM(min) {
  const h = Math.floor(min / 60)
  const m = Math.round(min % 60)
  return { h, m, text: h + 'h' + pad(m) + 'm' }
}

/** 两点间距离（米） */
function distance(lat1, lng1, lat2, lng2) {
  const R = 6371000
  const rad = x => (x * Math.PI) / 180
  const dLat = rad(lat2 - lat1)
  const dLng = rad(lng2 - lng1)
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2)
  return Math.round(2 * R * Math.asin(Math.sqrt(a)))
}

/* ---------- 交互反馈 ---------- */
function toast(title, icon, duration) {
  wx.showToast({ title: title, icon: icon || 'none', duration: duration || 1800 })
}
function loading(title) {
  wx.showLoading({ title: title || '加载中', mask: true })
}
function hideLoading() {
  wx.hideLoading()
}
function confirm(content, title) {
  return new Promise(resolve => {
    wx.showModal({
      title: title || '提示',
      content: content,
      confirmColor: '#3A6BF0',
      success: res => resolve(!!res.confirm),
      fail: () => resolve(false)
    })
  })
}

/**
 * 字符串哈希（FNV-1a + 末尾雪崩混合）
 * 说明：末尾的混合步骤不能省 —— 否则相邻种子（如 'xxx#001' 与 'xxx#002'）
 *       只会相差 1，取模后几乎落在同一区间，会导致整个阅览室的座位状态雷同。
 */
function hashCode(str) {
  let h = 2166136261
  const s = String(str)
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  h ^= h >>> 15
  h = Math.imul(h, 2246822507)
  h ^= h >>> 13
  h = Math.imul(h, 3266489909)
  h ^= h >>> 16
  return h >>> 0
}
/** 生成 [0,1) 的稳定伪随机数（同一 seed 结果一致，保证界面不闪变） */
function seededRandom(seed) {
  return (hashCode(seed) % 10000) / 10000
}

/** 数字千分位 */
function thousands(n) {
  return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/* ---------- 日期时间（预约 / 签到用） ---------- */

/** 当前时间 'YYYY-MM-DD HH:mm' */
function nowText() {
  return formatDate(new Date(), 'YYYY-MM-DD HH:mm')
}

/** 当前时间 + n 分钟，'YYYY-MM-DD HH:mm' */
function timeAfter(min) {
  return formatDate(new Date(Date.now() + (min || 0) * 60000), 'YYYY-MM-DD HH:mm')
}

/** 'YYYY-MM-DD HH:mm' → 毫秒时间戳（兼容 'YYYY-MM-DD'） */
function toTimestamp(t) {
  if (!t) return 0
  const d = new Date(String(t).replace(/-/g, '/'))
  return isNaN(d.getTime()) ? 0 : d.getTime()
}

/** 两个时间字符串相差多少分钟（b - a） */
function diffMinutes(a, b) {
  const ta = toTimestamp(a)
  const tb = toTimestamp(b)
  if (!ta || !tb) return 0
  return Math.max(0, Math.round((tb - ta) / 60000))
}

/** 分钟数 → '2时30分' / '45分' / '3时' */
function minutesText(min) {
  const m = Math.max(0, Math.round(min || 0))
  const h = Math.floor(m / 60)
  const rest = m % 60
  if (h && rest) return h + '时' + rest + '分'
  if (h) return h + '时'
  return rest + '分'
}

/** 毫秒数 → 'HH:mm:ss'，用于倒计时展示 */
function countdownText(ms) {
  const total = Math.max(0, Math.floor((ms || 0) / 1000))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  return (h ? h + ':' : '') + pad(m) + ':' + pad(s)
}

/** 'YYYY-MM-DD HH:mm' → 'HH:mm' */
function clockOf(t) {
  if (!t) return '--:--'
  return String(t).length >= 16 ? String(t).slice(11, 16) : String(t)
}

/** 'YYYY-MM-DD HH:mm' → 'MM-DD' */
function monthDayOf(t) {
  if (!t) return ''
  return String(t).slice(5, 10)
}

/** 相对日期文案：今天 / 昨天 / MM-DD */
function dayLabel(dateStr) {
  const todayStr = today()
  if (dateStr === todayStr) return '今天'
  const y = formatDate(new Date(Date.now() - 86400000), 'YYYY-MM-DD')
  if (dateStr === y) return '昨天'
  return monthDayOf(dateStr)
}

module.exports = {
  pad,
  formatDate,
  today,
  weekdayCN,
  dateOptions,
  toMinutes,
  toTime,
  durationText,
  minutesToHM,
  distance,
  toast,
  loading,
  hideLoading,
  confirm,
  hashCode,
  seededRandom,
  thousands,
  nowText,
  timeAfter,
  toTimestamp,
  diffMinutes,
  minutesText,
  countdownText,
  clockOf,
  monthDayOf,
  dayLabel
}
