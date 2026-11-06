/**
 * 座位业务状态 —— 四端统一定义
 * 依据《实际编码规范（最终统一开发版）》§5.1 / §39
 *
 * 前端只做"状态 -> 展示"的映射，绝不从 pressure_adc / pir_state 反推业务状态。
 * 业务状态一律由 Spring Boot 给出。
 */
export const SEAT_STATUS = {
  FREE: 0,
  RESERVED: 1,
  USING: 2,
  AWAY: 3,
  ALARM: 4
}

export const SEAT_STATUS_LABEL = {
  [SEAT_STATUS.FREE]: '空闲',
  [SEAT_STATUS.RESERVED]: '已预约',
  [SEAT_STATUS.USING]: '使用中',
  [SEAT_STATUS.AWAY]: '暂离',
  [SEAT_STATUS.ALARM]: '异常告警'
}

// §39：绿 / 黄 / 红 / 灰 / 红色闪烁
export const SEAT_STATUS_COLOR = {
  [SEAT_STATUS.FREE]: '#67C23A',
  [SEAT_STATUS.RESERVED]: '#E6A23C',
  [SEAT_STATUS.USING]: '#F56C6C',
  [SEAT_STATUS.AWAY]: '#909399',
  [SEAT_STATUS.ALARM]: '#F56C6C'
}

export const SEAT_STATUS_TAG = {
  [SEAT_STATUS.FREE]: 'success',
  [SEAT_STATUS.RESERVED]: 'warning',
  [SEAT_STATUS.USING]: 'danger',
  [SEAT_STATUS.AWAY]: 'info',
  [SEAT_STATUS.ALARM]: 'danger'
}

export function seatStatusLabel(status) {
  return SEAT_STATUS_LABEL[status] ?? `未知(${status})`
}

export function seatStatusColor(status) {
  return SEAT_STATUS_COLOR[status] ?? '#C0C4CC'
}

export function seatStatusTag(status) {
  return SEAT_STATUS_TAG[status] ?? 'info'
}

// ALARM 需要红色闪烁，其余状态不闪
export function isAlarmStatus(status) {
  return status === SEAT_STATUS.ALARM
}

/**
 * 预约状态（reservation.status）
 * 编码规范 §5.2，字符串枚举
 */
export const RESERVATION_STATUS = {
  RESERVED: 'RESERVED',
  USING: 'USING',
  AWAY: 'AWAY',
  COMPLETED: 'COMPLETED',
  CANCELLED: 'CANCELLED',
  TIMEOUT: 'TIMEOUT'
}

export const RESERVATION_STATUS_LABEL = {
  RESERVED: '待签到',
  USING: '使用中',
  AWAY: '暂离',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  TIMEOUT: '已超时'
}

export function reservationStatusLabel(status) {
  return RESERVATION_STATUS_LABEL[status] ?? status ?? '-'
}

/**
 * 违规类型 —— 只有三类，不扩展
 * 编码规范 §44
 */
export const VIOLATION_TYPE = {
  FAKE_OCCUPY: 'FAKE_OCCUPY',
  RESERVATION_TIMEOUT: 'RESERVATION_TIMEOUT',
  AWAY_TIMEOUT: 'AWAY_TIMEOUT'
}

export const VIOLATION_TYPE_LABEL = {
  FAKE_OCCUPY: '假占座',
  RESERVATION_TIMEOUT: '预约超时',
  AWAY_TIMEOUT: '暂离超时'
}

export const VIOLATION_TYPE_TAG = {
  FAKE_OCCUPY: 'danger',
  RESERVATION_TIMEOUT: 'warning',
  AWAY_TIMEOUT: 'info'
}

export function violationTypeLabel(type) {
  return VIOLATION_TYPE_LABEL[type] ?? type ?? '-'
}

/**
 * WebSocket 消息类型
 * 编码规范 §18
 */
export const WS_MESSAGE_TYPE = {
  SEAT_UPDATE: 'SEAT_UPDATE',
  RESERVATION_UPDATE: 'RESERVATION_UPDATE',
  ALARM: 'ALARM',
  DEVICE_STATUS: 'DEVICE_STATUS'
}
