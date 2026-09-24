/**
 * 预约成功 / 预约详情（同一页两种形态）
 * ------------------------------------------------------------
 * from=detail 时是「预约详情」，否则是「预约成功」结果页。
 * 展示字段与 ReservationVO 一致：seat_code / status / reserve_time /
 * sign_time / leave_time / return_time / release_time；
 * 阅览室、楼层、座位号来自 seat 表，由后端在 VO 里一并返回。
 * 数据全部来自后端，没有本地兜底。
 */
const app = getApp()
const api = require('../../utils/api')
const util = require('../../utils/util')
const config = require('../../utils/config')

Page({
  data: {
    statusBarHeight: 44,
    id: 0,
    fromDetail: false,
    r: null,
    statusText: '',
    statusTag: 'tag-grey',
    color: '#3A6BF0',
    weekday: '',
    durationText: '',
    remainText: '',
    reserveExpireMin: config.business.reserveExpireMin,
    busy: false,
    loading: true,
    error: ''
  },

  onLoad(options) {
    this.setData({
      statusBarHeight: (app.globalData && app.globalData.statusBarHeight) || 44,
      id: options.id || 0,
      fromDetail: options.from === 'detail'
    })
    this.load()
  },

  /** 详情页返回时刷新一下（比如刚从签到页操作完回来） */
  onShow() {
    if (this.data.r) this.load(true)
  },

  async load(silent) {
    if (!api.isConfigured()) {
      this.setData({ loading: false, error: '尚未配置后端地址：请在 utils/config.js 里填写 server.baseUrl' })
      return
    }
    if (!this.data.id) {
      this.setData({ loading: false, error: '缺少预约编号' })
      return
    }
    try {
      const r = await api.getReservationDetail(this.data.id)
      if (!r) {
        this.setData({ loading: false, error: '预约记录不存在' })
        return
      }
      const color = api.seatStatusColor(
        r.status === 'RESERVED' ? 1 : (r.status === 'USING' ? 2 : (r.status === 'AWAY' ? 3 : 0))
      )
      const duration = r.signTime
        ? util.minutesText(util.diffMinutes(r.signTime, r.releaseTime || util.nowText()))
        : ''
      this.setData({
        r: r,
        statusText: api.reserveStatusLabel(r.status),
        statusTag: api.reserveStatusTag(r.status),
        color: color,
        weekday: r.reserveTime ? util.weekdayCN(r.reserveTime.slice(0, 10)) : '',
        durationText: duration,
        loading: false,
        error: ''
      })
      this.tick()
    } catch (e) {
      if (!silent) this.setData({ loading: false, error: e.message || '加载失败' })
    }
  },

  /** 待签到状态显示到自动释放的倒计时 */
  tick() {
    const r = this.data.r
    if (!r) return
    if (r.status === 'RESERVED') {
      const ms = util.toTimestamp(r.reserveExpireAt) - Date.now()
      this.setData({ remainText: ms > 0 ? ('剩余 ' + util.countdownText(ms)) : '即将自动释放' })
    } else {
      this.setData({ remainText: this.data.durationText })
    }
  },

  /* ---------------- 操作 ---------------- */
  goSignin() {
    wx.redirectTo({ url: '/pages/signin/index?id=' + this.data.id })
  },

  goHome() {
    wx.switchTab({ url: '/pages/index/index' })
  },

  goMyReserve() {
    wx.switchTab({ url: '/pages/my-reserve/index' })
  },

  async onCancel() {
    if (this.data.busy) return
    const ok = await util.confirm('取消后该座位将立即释放，确定取消本次预约吗？', '确认取消？')
    if (!ok) return
    this.setData({ busy: true })
    try {
      const r = await api.cancelReservation(this.data.id)
      this.setData({ busy: false })
      util.toast('已取消预约')
      if (r) this.load(true)
    } catch (e) {
      this.setData({ busy: false })
      util.toast(e.message || '取消失败')
    }
  },

  back() {
    wx.navigateBack({ delta: 1 })
  }
})
