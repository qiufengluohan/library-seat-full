import request from '@/utils/request'

/**
 * GET /api/admin/rfid-binds
 * @returns {Promise<RfidBindVO[]>} 学生 / rfid_uid / 绑定时间
 */
export function getRfidBinds(params) {
  return request.get('/admin/rfid-binds', { params })
}

/**
 * POST /api/admin/rfid-binds
 * RfidBindDTO（编码规范 §10.4）：JSON 字段为 rfid_uid / user_id。
 * 绑定后，共享读卡器上报该 UID 时后端才能解析出学生并完成自动签到（方案 §26）。
 * @param {string} rfidUid
 * @param {number} userId
 */
export function bindRfid(rfidUid, userId) {
  return request.post('/admin/rfid-binds', {
    rfid_uid: rfidUid,
    user_id: userId
  })
}

/**
 * DELETE /api/admin/rfid-binds/{uid}
 * @param {string} rfidUid
 */
export function unbindRfid(rfidUid) {
  return request.delete(`/admin/rfid-binds/${encodeURIComponent(rfidUid)}`)
}
