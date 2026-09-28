/**
 * 我的
 * ------------------------------------------------------------
 * 本页把「编辑资料」融合进来了（原来独立的个人信息页已删除），
 * 页面上出现的每一个字段都直接来自数据表，表里没有值就留空：
 *
 *   user 表        nickname / avatar_url / openid / created_at / updated_at
 *   rfid_user 表   rfid_uid / bind_time（未绑定显示「暂无」）
 *
 * 可编辑的只有 nickname 与 avatar_url（对应 PUT /api/users/me），
 * 其余字段由后端维护，只读展示。
 *
 * 首页的「效率指标」已按需求删除；「学习统计」页已整体删除。
 */
const app = getApp()
const api = require('../../utils/api')
const util = require('../../utils/util')
const config = require('../../utils/config')

Page({
  data: {
    statusBarHeight: 44,
    version: config.appInfo.name + ' v' + config.appInfo.version,
    /** 表单里可编辑的字段 */
    form: { nickname: '', avatarUrl: '' },
    /** 表单里昵称为空时的展示名 */
    displayName: '',
    /** 只读字段（全部来自数据表） */
    info: {
      openid: '',
      createdAt: '',
      updatedAt: '',
      rfidUid: '',
      rfidBindTime: ''
    },
    /** 我的预约四态计数 */
    counts: { reserved: 0, using: 0, away: 0, finished: 0 },
    total: 0,
    latest: null,

    saving: false,
    loading: true,
    error: ''
  },

  onLoad() {
    this.setData({ statusBarHeight: (app.globalData && app.globalData.statusBarHeight) || 44 })
  },

  onShow() {
    if (!app.isLogin()) {
      app.toLogin()
      return
    }
    this.loadAll()
  },

  onPullDownRefresh() {
    this.loadAll().then(() => wx.stopPullDownRefresh())
  },

  async loadAll() {
    this.setData({ loading: true, error: '' })
    try {
      const res = await Promise.all([
        api.getUserProfile(),
        api.getReservations('ALL'),
        api.getCurrentReservation()
      ])
      const user = res[0] || {}
      const list = res[1] || []
      const current = res[2]

      const counts = { reserved: 0, using: 0, away: 0, finished: 0 }
      list.forEach(r => {
        if (r.status === 'RESERVED') counts.reserved++
        else if (r.status === 'USING') counts.using++
        else if (r.status === 'AWAY') counts.away++
        else counts.finished++
      })

      app.updateUserInfo(user)
      this.setData({
        form: { nickname: user.nickname || '', avatarUrl: user.avatarUrl || '' },
        displayName: user.nickname || config.business.defaultNickname,
        info: {
          openid: user.openid || '',
          createdAt: user.createdAt || '',
          updatedAt: user.updatedAt || '',
          rfidUid: user.rfidUid || '',
          rfidBindTime: user.rfidBindTime || ''
        },
        counts: counts,
        total: list.length,
        latest: current ? this.decorate(current) : null,
        loading: false
      })
    } catch (e) {
      this.setData({ loading: false, error: e.message || '加载失败' })
    }
  },

  decorate(r) {
    return Object.assign({}, r, {
      statusText: api.reserveStatusLabel(r.status),
      statusTag: api.reserveStatusTag(r.status),
      actionText: {
        RESERVED: '去签到',
        USING: '去操作',
        AWAY: '去回座'
      }[r.status] || '',
      date: r.reserveTime.slice(0, 10)
    })
  },

  /* ---------------- 表单 ---------------- */
  onChooseAvatar(e) {
    this.setData({ 'form.avatarUrl': e.detail.avatarUrl })
  },

  onNickInput(e) {
    this.setData({ 'form.nickname': e.detail.value })
  },

  async onSave() {
    if (this.data.saving) return
    this.setData({ saving: true })
    try {
      const user = await api.updateUserProfile({
        nickname: (this.data.form.nickname || '').trim(),
        avatarUrl: this.data.form.avatarUrl
      })
      app.updateUserInfo(user)
      this.setData({
        saving: false,
        form: { nickname: user.nickname || '', avatarUrl: user.avatarUrl || '' },
        displayName: user.nickname || config.business.defaultNickname,
        'info.updatedAt': user.updatedAt || ''
      })
      util.toast('已保存')
    } catch (e) {
      this.setData({ saving: false })
      util.toast(e.message || '保存失败')
    }
  },

  /* ---------------- 导航 ---------------- */
  goMyReserve(e) {
    const status = (e && e.currentTarget && e.currentTarget.dataset.status) || 'ALL'
    app.globalData.reserveFilter = status
    wx.switchTab({ url: '/pages/my-reserve/index' })
  },

  goSignin() {
    if (!this.data.latest) {
      util.toast('当前没有进行中的预约')
      return
    }
    wx.navigateTo({ url: '/pages/signin/index?id=' + this.data.latest.id })
  },

  goDetail() {
    if (!this.data.latest) return
    wx.navigateTo({ url: '/pages/reserve-success/index?id=' + this.data.latest.id + '&from=detail' })
  },

  onLogout() {
    util.confirm('退出后需要重新微信登录，确定退出吗？', '退出登录').then(ok => {
      if (!ok) return
      app.clearSession()
      app.toLogin()
    })
  }
})
