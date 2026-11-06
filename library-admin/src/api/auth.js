import request from '@/utils/request'

/**
 * POST /api/admin/login
 * 编码规范 §13 / §35：单管理员，密码后端用 BCrypt 校验，前端不做任何哈希。
 * @param {{username: string, password: string}} data
 * @returns {Promise<{token: string, user_id: number, nickname: string}>} LoginVO
 */
export function adminLogin(data) {
  return request.post('/admin/login', data)
}
