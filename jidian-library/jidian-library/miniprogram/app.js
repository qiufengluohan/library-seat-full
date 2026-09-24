/**
 * 机电图书馆 · 座位预约与学习管理小程序
 * app.js —— 全局入口
 * ------------------------------------------------------------
 * 职责：
 *  1. 维护登录态：token 由 utils/request.js 管理（存本地缓存），
 *     用户资料从后端 GET /api/users/me 拉取，本地只留一层缓存用于首屏展示；
 *  2. 维护全局系统信息与页面间共享状态。
 *
 * 说明：本小程序不保存任何业务数据，所有业务数据都以后端返回为准；
 *      后端地址未配置时不会发起任何请求，页面直接给出空态提示。
 */
const api = require('./utils/api')
const config = require('./utils/config')
const ws = require('./utils/ws')

App({
  globalData: {
    /** 用户资料（user 表 + rfid_user 表，来自后端） */
    userInfo: null,
    /** 后端地址是否已配置 */
    serverReady: false,
    /** 系统信息 */
    systemInfo: null,
    statusBarHeight: 20,
    /** 页面间共享：我的预约页待应用的筛选条件 */
    reserveFilter: ''
  },

  onLaunch() {
    this.globalData.serverReady = api.isConfigured()
    this.initSystemInfo()
    this.restoreSession()
  },

  initSystemInfo() {
    try {
      const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
      this.globalData.systemInfo = info
      this.globalData.statusBarHeight = info.statusBarHeight || 20
    } catch (e) {
      this.globalData.statusBarHeight = 20
    }
  },

  /** 恢复本地缓存的用户资料（仅用于首屏，真实数据以接口为准） */
  restoreSession() {
    const cached = wx.getStorageSync('userInfo')
    if (cached) this.globalData.userInfo = cached
  },

  /** 登录成功后写入会话 */
  setSession(userInfo) {
    this.globalData.userInfo = userInfo || null
    if (userInfo) wx.setStorageSync('userInfo', userInfo)
  },

  /** 局部更新用户资料 */
  updateUserInfo(userInfo) {
    this.globalData.userInfo = userInfo
    wx.setStorageSync('userInfo', userInfo)
  },

  /** 是否已登录（本地有 token 即视为已登录，真实鉴权由后端 401 决定） */
  isLogin() {
    return !!api.getToken()
  },

  /** 退出登录：断实时连接、清 token 与本地缓存 */
  clearSession() {
    // 后端按握手那一刻的 token 认人，不关连接的话它还会继续按旧账号广播，
    // 换账号登录时就变成两个身份的连接同时挂在服务端。
    ws.close()
    api.clearToken()
    this.globalData.userInfo = null
    wx.removeStorageSync('userInfo')
  },

  /** 未登录时统一跳登录页 */
  toLogin() {
    wx.reLaunch({ url: '/pages/login/index' })
  },

  /** 表里昵称为空时的展示名（微信登录不提供昵称） */
  displayName(userInfo) {
    const u = userInfo || this.globalData.userInfo || {}
    return u.nickname || config.business.defaultNickname
  }
})
