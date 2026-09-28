/**
 * 登录页 —— 仅微信登录
 * ------------------------------------------------------------
 * 按需求调整：
 *   ✗ 移除「使用已有账号进入」—— 之前未登录也能点进去，是登录态的漏洞；
 *   ✗ 移除头像选择与昵称输入 —— 微信登录不收集用户资料；
 *   ✓ 只保留一个「微信登录」按钮：wx.login 取 code → POST /api/auth/wechat-login
 *      → 后端用 code 换 openid，作为 user 表的唯一标识，并签发 token。
 *
 * 说明：微信小程序拿不到用户的微信号（官方无此接口），
 *       能拿到的唯一用户标识就是 openid，因此 user 表的身份以 openid 为准。
 *       昵称在表里默认为空，「我的」页展示为「微信用户」，用户可自行修改。
 */
const app = getApp()
const api = require('../../utils/api')
const util = require('../../utils/util')
const config = require('../../utils/config')

Page({
  data: {
    statusBarHeight: 44,
    submitting: false,
    version: config.appInfo.version,
    appName: config.appInfo.name,
    defaultNickname: config.business.defaultNickname
  },

  onLoad() {
    this.setData({
      statusBarHeight: (app.globalData && app.globalData.statusBarHeight) || 44
    })
    // 已登录直接进首页，避免出现「已登录还能停在登录页」的中间态
    if (app.isLogin()) {
      wx.switchTab({ url: '/pages/index/index' })
    }
  },

  /** 微信一键登录 */
  onWechatLogin() {
    if (this.data.submitting) return
    this.setData({ submitting: true })
    util.loading('正在登录')

    wx.login({
      success: res => {
        if (!res || !res.code) {
          util.hideLoading()
          this.setData({ submitting: false })
          util.toast('获取微信登录凭证失败，请重试')
          return
        }
        api.wechatLogin(res.code).then(result => {
          util.hideLoading()
          this.setData({ submitting: false })
          app.setSession(result.user)
          wx.switchTab({ url: '/pages/index/index' })
        }).catch(err => {
          util.hideLoading()
          this.setData({ submitting: false })
          util.toast(err.message || '登录失败，请重试')
        })
      },
      fail: () => {
        util.hideLoading()
        this.setData({ submitting: false })
        util.toast('微信登录未完成，请重试')
      }
    })
  },

  openAgreement() {
    wx.showModal({
      title: '用户协议与隐私政策',
      content: '本小程序用于图书馆座位预约与学习管理。登录仅获取你的微信用户标识（openid），' +
        '用于关联 user 表中的账号记录；不采集头像、昵称、手机号等个人信息，' +
        '也不会获取你的微信号。座位占用状态由馆内物联网传感器经后端提供。',
      showCancel: false,
      confirmColor: '#3A6BF0'
    })
  }
})
