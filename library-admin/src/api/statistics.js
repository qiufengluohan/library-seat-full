import request from '@/utils/request'

/**
 * GET /api/admin/statistics
 * @returns {Promise<StatisticsVO>} total_study_minutes / total_study_count / average_study_minutes
 */
export function getStatistics(params) {
  return request.get('/admin/statistics', { params })
}

/**
 * GET /api/admin/violations
 * 只做查询（方案 §35）。type 仅 FAKE_OCCUPY / RESERVATION_TIMEOUT / AWAY_TIMEOUT。
 * @param {{type?: string, seat_id?: number, page?: number, size?: number}} params
 */
export function getViolations(params) {
  return request.get('/admin/violations', { params })
}

/**
 * GET /api/admin/logs
 * 管理员操作日志（方案 §36）：强制释放、消除告警、修改阈值、测试蜂鸣器、RFID 绑定/解绑。
 * @param {{page?: number, size?: number}} params
 */
export function getLogs(params) {
  return request.get('/admin/logs', { params })
}
