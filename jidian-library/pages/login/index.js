/**
 * 登录页 —— 学号 + 密码
 * ------------------------------------------------------------
 * 本系统**不接微信登录**。学生用自己的学号自助注册（见 pages/register），
 * 之后用「学号 + 密码」登录。密码在**后端**用 BCrypt 校验，
 * 小程序端不做任何哈希、不缓存密码、不把它们写进任何 storage。
 *
 * 登录链路：
 *   POST /api/auth/login { student_no, password }
 *     → 后端查 user 表 → BCrypt 比对 → 签发 JWT
 *     → token 交给 utils/request.js 存本地，用户资料缓存进 app.globalData
 *
 * 顺带说明「为什么现在不用管微信 IP 白名单」：
 *   白名单只约束一个动作 —— 后端带 AppSecret 去调微信的 jscode2session。
 *   现在整条链路里没有任何一步会访问微信服务器，所以也就没有白名单问题。
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
    defaultNickname: config.business.defaultNickname,
    /** 登录表单：只在这个页面里存活，离开即销毁，不写本地缓存 */
    form: { studentNo: '', password: '' }
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

  onStudentNoInput(e) {
    this.setData({ 'form.studentNo': e.detail.value })
  },

  onPasswordInput(e) {
    this.setData({ 'form.password': e.detail.value })
  },

  /** 学号 + 密码登录 */
  onLogin() {
    if (this.data.submitting) return

    const studentNo = (this.data.form.studentNo || '').trim()
    const password = this.data.form.password || ''

    // 前端只检查"填没填"，格式与存在性都交给后端判断 ——
    // 在前端再堆一套和 DTO 重复的校验规则，两边一改就对不上，
    // 而且前端校验从来不是安全边界。
    if (!studentNo) {
      util.toast('请输入学号')
      return
    }
    if (!password) {
      util.toast('请输入密码')
      return
    }

    this.setData({ submitting: true })
    util.loading('正在登录')

    api.login(studentNo, password).then(result => {
      util.hideLoading()
      // 登录成功后立刻把密码从表单里清掉：页面还活着的时候
      // 密码就不该继续留在 data 里（微信开发者工具的调试面板能看到它）。
      this.setData({ submitting: false, 'form.password': '' })
      app.setSession(result.user)
      wx.switchTab({ url: '/pages/index/index' })
    }).catch(err => {
      util.hideLoading()
      this.setData({ submitting: false })
      util.toast(err.message || '登录失败，请重试')
    })
  },

  /** 去注册页 */
  goRegister() {
    wx.navigateTo({ url: '/pages/register/index' })
  },

  openAgreement() {
    // 文案必须与代码实际行为一致：
    // 注册收学号与密码（密码 BCrypt 存），头像与昵称是用户在「我的」页自愿填的。
    wx.showModal({
      title: '用户协议与隐私政策',
      content: '本小程序用于图书馆座位预约与学习管理。\n' +
        '1. 注册时采集你的学号与密码：学号用作账号标识，' +
        '密码在后端以 BCrypt 加密存储，不保存明文，也不会下发回客户端。\n' +
        '2. 头像与昵称由你在「我的」页自愿填写，可随时修改或清空；不填也能正常使用，' +
        '它们只保存在本馆的服务器上。\n' +
        '3. 不采集手机号、位置等其他个人信息。\n' +
        '4. 校园卡号由馆内读卡器刷卡时写入，小程序端只读展示。\n' +
        '5. 座位占用状态由馆内物联网传感器上报后端后提供。\n' +
        '以上数据仅用于本馆座位管理，不向第三方提供。',
      showCancel: false,
      confirmColor: '#3A6BF0'
    })
  }
})
