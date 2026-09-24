import dayjs from 'dayjs'

const DATE_TIME_FORMAT = 'YYYY-MM-DD HH:mm:ss'
const TIME_FORMAT = 'HH:mm:ss'

export function formatDateTime(value) {
  if (!value) return '-'
  const date = dayjs(value)
  return date.isValid() ? date.format(DATE_TIME_FORMAT) : '-'
}

export function formatTime(value) {
  if (!value) return '-'
  const date = dayjs(value)
  return date.isValid() ? date.format(TIME_FORMAT) : '-'
}

/**
 * 分钟数 -> "x小时y分钟"。学习时长统计用（编码规范 §43）。
 */
export function formatMinutes(minutes) {
  const value = Number(minutes)
  if (!Number.isFinite(value) || value <= 0) return '0分钟'
  const hours = Math.floor(value / 60)
  const rest = value % 60
  if (hours === 0) return `${rest}分钟`
  return rest === 0 ? `${hours}小时` : `${hours}小时${rest}分钟`
}

/**
 * 最后上报时间 -> "x秒前 / x分钟前"，用于设备管理页判断在线情况。
 */
export function formatRelative(value) {
  if (!value) return '-'
  const date = dayjs(value)
  if (!date.isValid()) return '-'

  const seconds = dayjs().diff(date, 'second')
  if (seconds < 0) return formatDateTime(value)
  if (seconds < 60) return `${seconds}秒前`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}分钟前`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}小时前`
  return formatDateTime(value)
}
