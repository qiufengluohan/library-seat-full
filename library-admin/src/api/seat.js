import request from '@/utils/request'

/**
 * GET /api/admin/seats
 * 返回全馆座位合成状态。status/alarm/online 全部由 Spring Boot 给出，
 * 前端不得根据 pressure_adc 自行推断（方案 §5）。
 * @returns {Promise<SeatVO[]>}
 */
export function getSeats(params) {
  return request.get('/admin/seats', { params })
}

/**
 * GET /api/admin/seats/{id}
 * @returns {Promise<SeatDetailVO>} 含 pressure_adc / pir_state / last_report_at
 */
export function getSeatDetail(seatId) {
  return request.get(`/admin/seats/${encodeURIComponent(seatId)}`)
}

/**
 * POST /api/admin/seats/{id}/clear-alarm
 * 编码规范 §14.10：把 seat_shadow.alarm_flag 置 0，必要时同步设备，并写 operation_log。
 */
export function clearAlarm(seatId) {
  return request.post(`/admin/seats/${encodeURIComponent(seatId)}/clear-alarm`)
}

/**
 * POST /api/admin/seats/{id}/force-release
 * 编码规范 §14.9：关闭当前订单 -> seat 置 FREE -> study_record -> operation_log
 * -> OneNET 下发显示恢复 -> WebSocket 广播。
 */
export function forceRelease(seatId) {
  return request.post(`/admin/seats/${encodeURIComponent(seatId)}/force-release`)
}
