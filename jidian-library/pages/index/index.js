/**
 * 首页
 * ------------------------------------------------------------
 * 结构（在本轮需求里做了删减）：
 *   1. 顶部导航栏 —— 站名 / 我的预约 / 搜索框
 *   2. 预约座位主入口 —— 唯一活入口，进入座位页
 *   3. 当前预约卡 —— **动态**：没有进行中的预约就整块不渲染
 *   4. 快捷功能 3 项 —— 我的预约 / 签到·离座 / 意见反馈
 *   5. 阅览室速览 —— 由 GET /api/seats 的 area / floor 归并
 *   6. 问题反馈卡
 *   7. 页脚品牌条
 *   8. 搜索浮层
 *
 * ✗ 已移除：公告轮播（没有对应的数据表与接口）、无预约占位卡、
 *           学习统计快捷入口、智能座位节点说明卡、学习统计页。
 *
 * 数据全部来自后端；未配置后端地址时只显示空态提示，不显示任何假数据。
 */
const app = getApp()
const api = require('../../utils/api')
const util = require('../../utils/util')
const ws = require('../../utils/ws')

Page({
  data: {
    statusBarHeight: 44,
    userInfo: null,
    current: null,
    rooms: [],
    seatTotal: 0,
    searchOpen: false,
    keyword: '',
    searchResult: [],
    loading: true,
    error: ''
  },

  onLoad() {
    this.setData({
      statusBarHeight: (app.globalData && app.globalData.statusBarHeight) || 44,
      userInfo: app.globalData.userInfo
    })
    this.load()
  },

  onShow() {
    if (!app.isLogin()) {
      app.toLogin()
      return
    }
    this.setData({ userInfo: app.globalData.userInfo })
    this.loadCurrent()
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
  },

  /**
   * 后端只推 SEAT_UPDATE，不推 RESERVATION_UPDATE —— 订单的每一次跃迁都必然
   * 伴随那个座位的业务状态变化，所以按 seatId 对上就行。
   * 首页除了「当前预约」卡片没有别的实时数据（阅览室速览只有总数，不随状态变），
   * 因此别的座位怎么变都不用理。
   */
  onSeatEvent(msg) {
    const current = this.data.current
    if (!current || !msg || msg.seatId !== current.seatId) return
    this.loadCurrent()
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true, error: '' })

    if (!api.isConfigured()) {
      this.setData({
        loading: false,
        rooms: [],
        seatTotal: 0,
        error: '尚未配置后端地址：请在 utils/config.js 里填写 server.baseUrl'
      })
      return
    }

    try {
      const res = await Promise.all([api.getOverview(), api.getCurrentReservation()])
      const overview = res[0]
      this.setData({
        rooms: overview.rooms,
        seatTotal: overview.stats.total,
        current: res[1] ? this.decorate(res[1]) : null,
        loading: false
      })
    } catch (e) {
      this.setData({
        loading: false,
        rooms: [],
        seatTotal: 0,
        error: e.message || '数据加载失败'
      })
    }
  },

  /** 只刷新「当前预约」，用于预约完成后回到首页 */
  async loadCurrent() {
    if (!api.isConfigured()) return
    try {
      const r = await api.getCurrentReservation()
      this.setData({ current: r ? this.decorate(r) : null })
    } catch (e) {
      this.setData({ current: null })
    }
  },

  decorate(r) {
    return Object.assign({}, r, {
      statusLabel: api.reserveStatusLabel(r.status),
      statusTag: api.reserveStatusTag(r.status),
      actionText: {
        RESERVED: '去签到',
        USING: '去操作',
        AWAY: '去回座'
      }[r.status] || '查看'
    })
  },

  /* ---------------- 导航 ---------------- */

  /** 预约座位主入口 —— 座位页是预约流程的起点 */
  goReserve(e) {
    const roomKey = (e && e.currentTarget && e.currentTarget.dataset.key) || ''
    const q = roomKey ? ('?roomKey=' + encodeURIComponent(roomKey)) : ''
    wx.navigateTo({ url: '/pages/seats/index' + q })
  },

  goSignin() {
    const cur = this.data.current
    if (!cur) {
      util.toast('当前没有进行中的预约')
      return
    }
    wx.navigateTo({ url: '/pages/signin/index?id=' + cur.id })
  },

  goMyReserve() {
    wx.switchTab({ url: '/pages/my-reserve/index' })
  },

  goFeedback() {
    wx.navigateTo({ url: '/pages/feedback/index' })
  },

  goDetail() {
    const cur = this.data.current
    if (!cur) return
    wx.navigateTo({ url: '/pages/reserve-success/index?id=' + cur.id + '&from=detail' })
  },

  /* ---------------- 搜索 ---------------- */
  openSearch() {
    this.setData({ searchOpen: true, keyword: '', searchResult: [] })
  },
  closeSearch() {
    this.setData({ searchOpen: false })
  },
  onSearchInput(e) {
    const kw = e.detail.value.trim()
    const rooms = this.data.rooms.filter(
      r => r.name.indexOf(kw) > -1 || (r.floor || '').indexOf(kw) > -1
    )
    this.setData({ keyword: kw, searchResult: kw ? rooms : [] })
  },
  noop() {}
})
