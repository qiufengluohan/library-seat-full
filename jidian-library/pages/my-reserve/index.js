/**
 * 我的预约
 * ------------------------------------------------------------
 * 数据全部来自后端 GET /api/reservations（一次拉全量，前端做筛选与计数）。
 * 筛选 tab 按《实际编码规范》5.2 的预约状态组织：
 *   全部 / 待签到(RESERVED) / 使用中(USING) / 暂离(AWAY) / 已结束(COMPLETED+CANCELLED+TIMEOUT)
 *
 * 列表里没有任何初始记录 —— 数据库里没有数据就是空列表。
 */
const app = getApp()
const api = require('../../utils/api')
const util = require('../../utils/util')
const ws = require('../../utils/ws')

const TABS = [
  { key: 'ALL', label: '全部' },
  { key: 'RESERVED', label: '待签到' },
  { key: 'USING', label: '使用中' },
  { key: 'AWAY', label: '暂离' },
  { key: 'FINISHED', label: '已结束' }
]

const FINISHED = ['COMPLETED', 'CANCELLED', 'TIMEOUT']

Page({
  data: {
    statusBarHeight: 44,
    tabs: TABS,
    active: 'ALL',
    counts: { ALL: 0, RESERVED: 0, USING: 0, AWAY: 0, FINISHED: 0 },
    groups: [],
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
    const preset = app.globalData.reserveFilter
    if (preset) {
      app.globalData.reserveFilter = ''
      this.setData({ active: preset })
    }
    this.load()
    this.offWs = ws.subscribe(msg => this.onSeatEvent(msg))
  },

  onHide() {
    this.releaseWs()
  },

  onUnload() {
    this.releaseWs()
  },

  releaseWs() {
    if (this.offWs) this.offWs()
    this.offWs = null
    if (this.wsRefreshTimer) {
      clearTimeout(this.wsRefreshTimer)
      this.wsRefreshTimer = null
    }
  },

  /**
   * 只有"我还没结束的那几单所在的座位"变了才刷新 ——
   * 超时释放、管理员强制释放都会推 SEAT_UPDATE，用户不该还盯着一个已失效的状态。
   * 别人预约了别的座位与本页无关。
   */
  onSeatEvent(msg) {
    if (!this.liveSeatIds || !this.liveSeatIds[msg.seatId]) return
    if (this.wsRefreshTimer) return
    this.wsRefreshTimer = setTimeout(() => {
      this.wsRefreshTimer = null
      this.load(true)
    }, 400)
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh())
  },

  async load(silent) {
    if (!silent) this.setData({ loading: true, error: '' })

    if (!api.isConfigured()) {
      this.setData({ loading: false, groups: [], error: '尚未配置后端地址：请在 utils/config.js 里填写 server.baseUrl' })
      return
    }

    try {
      const all = await api.getReservations('ALL')
      const counts = { ALL: 0, RESERVED: 0, USING: 0, AWAY: 0, FINISHED: 0 }
      all.forEach(r => {
        counts.ALL++
        if (counts[r.status] !== undefined) counts[r.status]++
        else if (FINISHED.indexOf(r.status) > -1) counts.FINISHED++
      })

      // 后端没有 RESERVATION_UPDATE 这种消息，只能靠 seatId 把推送对上到订单，
      // 所以要把"还活着的订单所在座位"记下来
      this.liveSeatIds = {}
      all.forEach(r => {
        if (['RESERVED', 'USING', 'AWAY'].indexOf(r.status) > -1 && r.seatId) {
          this.liveSeatIds[r.seatId] = true
        }
      })

      const list = this.data.active === 'ALL'
        ? all
        : all.filter(r => this.data.active === 'FINISHED'
          ? FINISHED.indexOf(r.status) > -1
          : r.status === this.data.active)

      this.setData({
        counts: counts,
        groups: this.group(list.map(r => this.decorate(r))),
        loading: false
      })
    } catch (e) {
      this.setData({
        loading: false,
        groups: silent ? this.data.groups : [],
        error: e.message || '加载失败'
      })
    }
  },

  decorate(r) {
    const canSignin = r.status === 'RESERVED' || r.status === 'USING' || r.status === 'AWAY'
    return Object.assign({}, r, {
      statusText: api.reserveStatusLabel(r.status),
      statusTag: api.reserveStatusTag(r.status),
      date: r.reserveTime.slice(0, 10),
      reserveClock: util.clockOf(r.reserveTime),
      signClock: r.signTime ? util.clockOf(r.signTime) : '--:--',
      releaseClock: r.releaseTime ? util.clockOf(r.releaseTime) : '--:--',
      canCancel: r.status === 'RESERVED',
      canSignin: canSignin,
      actionText: {
        RESERVED: '去签到',
        USING: '去操作',
        AWAY: '去回座'
      }[r.status] || ''
    })
  },

  /** 按预约日期分组（今天 / 昨天 / MM-DD） */
  group(list) {
    const map = {}
    const order = []
    list.forEach(r => {
      const d = r.date || '未知日期'
      if (!map[d]) {
        map[d] = { date: d, label: r.date ? util.dayLabel(r.date) : '未知日期', list: [] }
        order.push(d)
      }
      map[d].list.push(r)
    })
    return order.map(d => map[d])
  },

  onTab(e) {
    const key = e.currentTarget.dataset.key
    if (key === this.data.active) return
    this.setData({ active: key })
    this.load()
  },

  goReserve() {
    wx.navigateTo({ url: '/pages/seats/index' })
  },

  goSignin(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    wx.navigateTo({ url: '/pages/signin/index?id=' + id })
  },

  async onCancel(e) {
    const id = e.currentTarget.dataset.id
    const ok = await util.confirm('取消后该座位将立即释放，确定取消本次预约吗？', '确认取消？')
    if (!ok) return
    try {
      await api.cancelReservation(id)
      util.toast('已取消预约')
      this.load()
    } catch (err) {
      util.toast(err.message || '取消失败')
    }
  },

  /** 占座反馈 → 意见反馈页 */
  onReport(e) {
    const item = e.currentTarget.dataset.item
    if (!item) return
    const info = item.area + ' ' + item.seatNo + '号（' + item.statusText + '）'
    wx.navigateTo({ url: '/pages/feedback/index?seatInfo=' + encodeURIComponent(info) })
  },

  viewDetail(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    wx.navigateTo({ url: '/pages/reserve-success/index?id=' + id + '&from=detail' })
  }
})
