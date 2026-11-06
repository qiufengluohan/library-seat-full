/**
 * 通用工具方法
 * ------------------------------------------------------------
 * 说明：本文件只保留**页面实际用到**的方法。
 *
 * 已按需求移除（原属于被删掉的「选择时间」「学习统计」「设备与硬件」等页面）：
 *   dateOptions   横向日期选择器（随「选择时间」页一起删）
 *   toMinutes     'HH:mm' → 分钟数（只服务于 durationText）
 *   toTime        分钟数 → 'HH:mm'（同上）
 *   durationText  '2时30分' 时长文案（同上；页面改用 minutesText）
 *   minutesToHM   '2h30m' 统计专用（随「学习统计」页一起删）
 *   distance      两点间距离（哈弗辛公式，随「设备与硬件 / 附近」一起删）
 *   hashCode      字符串哈希 ┐
 *   seededRandom  稳定伪随机 ┘ 已删本地假数据层 staticData.js 的种子函数，
 *                 小程序端现在**不生成任何业务数据**，全部以后端返回为准
 *   thousands     数字千分位（随「学习统计」页一起删）
 *   timeAfter     当前时间 +n 分钟（页面改用后端下发的 *_expire_at）
 *
 * 另外 pad / formatDate / today / monthDayOf 只在本文件内部被调用，
 * 已改为**不再导出**，避免再出现"导出了但没人用"的假接口。
 */
const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

/** 两位补零（内部使用） */
function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

/**
 * 格式化日期：formatDate(new Date(), 'YYYY-MM-DD')（内部使用）
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

/** 今天 YYYY-MM-DD（内部使用） */
function today() {
  return formatDate(new Date(), 'YYYY-MM-DD')
}

/** 星期中文：weekdayCN('2026-09-28') → '周一' */
function weekdayCN(dateStr) {
  const d = new Date((dateStr || today()).replace(/-/g, '/'))
  return WEEK[d.getDay()]
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

/* ---------- 日期时间 ---------- */

/** 当前时间 'YYYY-MM-DD HH:mm' */
function nowText() {
  return formatDate(new Date(), 'YYYY-MM-DD HH:mm')
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

/** 'YYYY-MM-DD HH:mm' → 'MM-DD'（内部使用，dayLabel 用它做兜底） */
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
  /* 日期 */
  weekdayCN,
  nowText,
  toTimestamp,
  diffMinutes,
  minutesText,
  countdownText,
  clockOf,
  dayLabel,
  /* 交互反馈 */
  toast,
  loading,
  hideLoading,
  confirm
}
