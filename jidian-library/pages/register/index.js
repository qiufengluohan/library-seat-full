/**
 * 注册页 —— 学号 + 密码
 * ------------------------------------------------------------
 * 本系统不接微信登录，学生第一次使用要先在这里建账号。
 *
 * 链路：
 *   POST /api/auth/register { student_no, password, nickname? }
 *     → 后端查重（student_no 上有 UNIQUE 索引兜底并发）
 *     → BCrypt 加密 → 建档 → 直接签发 JWT
 *     → 前端拿到 token + 用户资料，等于"注册完就登录好了"，不用再跳回登录页
 *
 * 前端的格式校验只是为少一次往返（学号字母数字、密码 6~32 位、两次密码一致），
 * 真正说了算的是后端的 DTO 校验，两边规则刻意对齐：
 *   StudentRegisterDTO：@Pattern ^[A-Za-z0-9]{4,32}$、@Size(min=6, max=32)
 *   ⇒ 改任意一边都要同步改另一边，否则会出现"前端放行、后端 400"。
 */
const app = getApp()
const api = require('../../utils/api')
const util = require('../../utils/util')

Page({
  data: {
    statusBarHeight: 44,
    submitting: false,
    form: { studentNo: '', password: '', confirm: '', nickname: '' }
  },

  onLoad() {
    this.setData({
      statusBarHeight: (app.globalData && app.globalData.statusBarHeight) || 44
    })
    // 已经登录了就没必要再注册
    if (app.isLogin()) {
      wx.switchTab({ url: '/pages/index/index' })
    }
  },

  /** 四个输入框共用一个 handler，靠 data-key 区分，省掉四个几乎一样的函数 */
  onInput(e) {
    const key = e.currentTarget.dataset.key
    this.setData({ ['form.' + key]: e.detail.value })
  },

  /**
   * 表单校验。返回 null 表示通过，否则返回要提示的文案。
   * 逐条对应后端 StudentRegisterDTO 的约束。
   */
  validate() {
    const f = this.data.form
    const studentNo = (f.studentNo || '').trim()
    const password = f.password || ''
    const confirm = f.confirm || ''

    if (!studentNo) return '请输入学号'
    if (!/^[A-Za-z0-9]{4,32}$/.test(studentNo)) return '学号只能由 4~32 位字母或数字组成'
    if (!password) return '请输入密码'
    if (password.length < 6 || password.length > 32) return '密码长度需在 6~32 位之间'
    if (password !== confirm) return '两次输入的密码不一致'
    return null
  },

  onRegister() {
    if (this.data.submitting) return

    const err = this.validate()
    if (err) {
      util.toast(err)
      return
    }

    const studentNo = (this.data.form.studentNo || '').trim()
    const password = this.data.form.password || ''
    const nickname = (this.data.form.nickname || '').trim()

    this.setData({ submitting: true })
    util.loading('正在注册')

    api.register(studentNo, password, nickname).then(result => {
      util.hideLoading()
      // 注册成功后端已经签好 token，直接进首页。
      // 顺手把表单清空：页面这时还没销毁，密码不该继续留在 data 里。
      this.setData({
        submitting: false,
        form: { studentNo: '', password: '', confirm: '', nickname: '' }
      })
      app.setSession(result.user)
      wx.switchTab({ url: '/pages/index/index' })
    }).catch(e => {
      util.hideLoading()
      this.setData({ submitting: false })
      util.toast(e.message || '注册失败，请重试')
    })
  },

  goBack() {
    // 从登录页 navigateTo 进来，正常有一层可以返回；
    // 万一是被直接打开（没有上一页），退回登录页，而不是把用户留在空页面上。
    const pages = getCurrentPages()
    if (pages.length > 1) {
      wx.navigateBack({ delta: 1 })
    } else {
      wx.reLaunch({ url: '/pages/login/index' })
    }
  }
})
