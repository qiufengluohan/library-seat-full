import request from '@/utils/request'

/**
 * GET /api/admin/devices
 * @returns {Promise<DeviceVO[]>} device_id / seat_id / seat_code / online /
 *   pressure_adc / pir_state / alarm_flag / last_report_at
 */
export function getDevices() {
  return request.get('/admin/devices')
}

/**
 * POST /api/admin/devices/{id}/config
 * 修改 ADC 阈值。后端翻译成 OneNET 属性下发 {"property":"adc_threshold","value":n}，
 * 前端绝不直接拼 OneNET 请求（编码规范 §42）。
 * @param {string} deviceId 设备编号，如 SEAT_001
 * @param {number} adcThreshold
 */
export function updateDeviceConfig(deviceId, adcThreshold) {
  return request.post(`/admin/devices/${encodeURIComponent(deviceId)}/config`, {
    adc_threshold: adcThreshold
  })
}

/**
 * POST /api/admin/devices/{id}/buzzer
 * 蜂鸣器测试。后端翻译成 OneNET 服务调用 {"service":"buzzer_ctrl","duration_ms":n}。
 * @param {string} deviceId
 * @param {number} durationMs
 */
export function testBuzzer(deviceId, durationMs = 1000) {
  return request.post(`/admin/devices/${encodeURIComponent(deviceId)}/buzzer`, {
    duration_ms: durationMs
  })
}
