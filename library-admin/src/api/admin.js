import request from '@/utils/request'

/**
 * GET /api/admin/dashboard
 * @returns {Promise<DashboardVO>} 座位/设备/当天统计的聚合快照
 */
export function getDashboard() {
  return request.get('/admin/dashboard')
}
