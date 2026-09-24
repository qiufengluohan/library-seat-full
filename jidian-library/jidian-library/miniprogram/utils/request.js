/**
 * HTTP 请求封装（小程序 → Spring Boot）
 * ------------------------------------------------------------
 * 这是整个小程序里**唯一**发起 HTTP 请求的文件，其他页面与工具模块
 * 一律通过 utils/api.js 取数。实时推送走 utils/ws.js（唯一的另一种网络出口）。
 *
 * 后端统一返回（规范第十二节）：
 *   { "code": 200, "message": "success", "data": {...} }
 * 错误码：200 成功 / 400 参数错误 / 401 未登录 / 403 无权限 /
 *         404 数据不存在 / 409 业务冲突 / 500 服务器错误
 *
 * 鉴权：登录后把 token 存本地，之后每个请求带
 *       Authorization: Bearer {token}
 */
const config = require('./config')

/** 后端地址（去掉末尾斜杠） */
function baseUrl() {
  const u = (config.server && config.server.baseUrl) || ''
  return u.replace(/\/+$/, '')
}

/** 是否已经配置后端地址 */
function isConfigured() {
  return !!baseUrl()
}

/* ============================================================
 * token
 * ========================================================== */
function tokenKey() {
  return (config.server && config.server.tokenKey) || 'jidian_token'
}

function getToken() {
  return wx.getStorageSync(tokenKey()) || ''
}

function setToken(token) {
  if (token) wx.setStorageSync(tokenKey(), token)
}

function clearToken() {
  wx.removeStorageSync(tokenKey())
}

/* ============================================================
 * 错误
 * ========================================================== */
function makeError(code, message, kind) {
  const e = new Error(message || '请求失败')
  e.code = code
  e.kind = kind || 'api'
  return e
}

const STATUS_TEXT = {
  400: '请求参数有误',
  401: '登录已失效，请重新登录',
  403: '没有访问权限',
  404: '数据不存在',
  405: '接口方法不被支持',
  409: '操作与当前状态冲突',
  500: '服务器内部错误'
}

/* ============================================================
 * 底层请求
 * ========================================================== */
function send(method, path, data) {
  if (!isConfigured()) {
    return Promise.reject(makeError(
      'unconfigured',
      '尚未配置后端地址，请在 utils/config.js 里填写 server.baseUrl',
      'unconfigured'
    ))
  }

  const url = baseUrl() + path
  const token = getToken()

  return new Promise((resolve, reject) => {
    wx.request({
      url: url,
      method: method,
      data: method === 'GET' ? undefined : (data || {}),
      timeout: (config.server && config.server.timeout) || 10000,
      header: {
        'content-type': 'application/json',
        Authorization: token ? 'Bearer ' + token : ''
      },
      success: res => {
        const body = res.data || {}
        const httpStatus = res.statusCode

        // 未登录 / token 过期
        if (httpStatus === 401) {
          clearToken()
          reject(makeError(401, '登录已失效，请重新登录', 'unauthorized'))
          return
        }
        if (httpStatus < 200 || httpStatus >= 300) {
          reject(makeError(
            httpStatus,
            body.message || STATUS_TEXT[httpStatus] || ('HTTP ' + httpStatus),
            'http'
          ))
          return
        }

        // 标准返回体 { code, message, data }
        if (typeof body.code !== 'undefined') {
          if (body.code === 200) {
            resolve(body.data)
          } else {
            reject(makeError(body.code, body.message || STATUS_TEXT[body.code]))
          }
          return
        }

        // 兼容：后端直接返回了裸数据
        resolve(body)
      },
      fail: err => {
        const msg = (err && err.errMsg) || ''
        if (/timeout/i.test(msg)) {
          reject(makeError('timeout', '请求超时，请检查网络或后端服务', 'timeout'))
        } else {
          reject(makeError('network',
            '网络不可达：请确认后端已启动、地址正确，且已在开发者工具勾选「不校验合法域名」',
            'network'))
        }
      }
    })
  })
}

module.exports = {
  isConfigured: isConfigured,
  baseUrl: baseUrl,
  getToken: getToken,
  setToken: setToken,
  clearToken: clearToken,

  get: (path, data) => send('GET', path, data),
  post: (path, data) => send('POST', path, data),
  put: (path, data) => send('PUT', path, data),
  del: (path, data) => send('DELETE', path, data)
}
