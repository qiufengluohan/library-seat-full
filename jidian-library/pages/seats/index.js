/**
 * 座位页 —— 预约流程的起点
 * ------------------------------------------------------------
 * 数据全部来自后端接口：
 *   阅览室    —— 由 GET /api/seats 的 area / floor 归并得到（阅览室不做独立表）
 *   座位与状态 —— GET /api/seats 返回的 seat.status（0~4）与 online
 *
 * 流程：座位页 → 点座位 → 确认弹窗 → 预约成功
 * 创建预约只向后端提交 seat_id（规范第十四节 14.3）。
 *
 * 未配置后端地址或后端不可用时，本页不显示任何座位数据、也不允许预约，
 * 只给出明确的空态提示 —— 不会出现「没接后端却能预约」的情况。
 */
const app = getApp()
const api = require('../../utils/api')
const util = require('../../utils/util')
const config = require('../../utils/config')
const ws = require('../../utils/ws')

Page({
  data: {
    statusBarHeight: 44,
    legend: config.seatStatus,
    reserveExpireMin: config.business.reserveExpireMin,

    rooms: [],
    roomKey: '',
    room: null,
    rows: [],
    cellW: 60,
    stats: { total: 0, free: 0, reserved: 0, using: 0, away: 0, alarm: 0, offline: 0 },

    selectedSeat: null,
    loading: true,
    error: '',
    confirming: false,
    showConfirm: false
  },

  onLoad(options) {
    this.setData({
      statusBarHeight: (app.globalData && app.globalData.statusBarHeight) || 44
    })
    this.presetRoomKey = options.roomKey || ''
    this.load()
  },

  onShow() {
    if (!this.data.loading) this.load(true)
    // 实时同步（§46）：设备上报 → 后端广播 → 本页面静默重拉一次
    this.offWs = ws.subscribe(msg => this.onSeatEvent(msg))
  },

  onUnload() {
    this.releaseWs()
  },

  onHide() {
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
   * 座位状态有变化。只关心当前这个阅览室里的座位，
   * 别的阅览室有人开卡不该触发本页刷新。
   */
  onSeatEvent(msg) {
    if (!this.onScreenSeats || !this.onScreenSeats[msg.seatId]) return
    // 一台设备一次上报会连着推好几条（状态 + 告警 + 在线），合并成一次请求
    if (this.wsRefreshTimer) return
    this.wsRefreshTimer = setTimeout(() => {
      this.wsRefreshTimer = null
      this.load(true)
    }, 400)
  },

  onPullDownRefresh() {
    this.load(true).then(() => wx.stopPullDownRefresh())
  },

  /* ---------------- 数据 ---------------- */
  async load(silent) {
    if (!silent) this.setData({ loading: true, error: '', selectedSeat: null })

    if (!api.isConfigured()) {
      this.setData({
        loading: false,
        error: '尚未配置后端地址：请在 utils/config.js 里填写 server.baseUrl，' +
          '并确认 Spring Boot 服务已启动。'
      })
      return
    }

    try {
      const res = await api.getSeatMap(this.data.roomKey || this.presetRoomKey)
      const room = res.room

      if (!room) {
        this.onScreenSeats = {}
        this.setData({ loading: false, error: '后端暂未返回任何座位数据', rooms: [] })
        return
      }

      // 静默刷新时按 seatCode 找回已选座位，避免把用户的选择刷掉
      let selected = null
      if (silent && this.data.selectedSeat) {
        const hit = res.seats.filter(s => s.seatCode === this.data.selectedSeat.seatCode)[0]
        if (hit && hit.status === 0 && hit.online !== false) selected = hit
        else if (hit) util.toast('您选中的座位状态已变化，请重新选择')
      }

      this.onScreenSeats = {}
      res.seats.forEach(s => { this.onScreenSeats[s.id] = true })

      this.setData({
        rooms: res.rooms || [],
        roomKey: room.key,
        room: room,
        stats: res.stats,
        cellW: this.calcCellW(res.layout),
        rows: this.buildGrid(res.seats, res.layout),
        selectedSeat: selected,
        loading: false,
        error: ''
      })
    } catch (e) {
      this.onScreenSeats = {}
      this.setData({
        loading: false,
        rows: [],
        stats: { total: 0, free: 0, reserved: 0, using: 0, away: 0, alarm: 0, offline: 0 },
        error: e.message || '座位数据加载失败'
      })
    }
  },

  calcCellW(layout) {
    // 内容区宽度 ≈ 750 - 页面左右内边距 56 - 卡片内边距 56
    const contentW = 638
    const gap = 6
    const cols = layout.cols || 5
    const w = Math.floor((contentW - gap * (cols - 1)) / cols)
    return Math.max(34, Math.min(96, w))
  },

  buildGrid(seats, layout) {
    const map = {}
    seats.forEach(s => { map[s.row + '_' + s.col] = s })
    const rows = []
    for (let r = 1; r <= (layout.rows || 1); r++) {
      const cells = []
      for (let c = 1; c <= (layout.cols || 1); c++) {
        const s = map[r + '_' + c]
        if (s) {
          cells.push(Object.assign({
            cellType: 'seat',
            short: s.seatNo.slice(-2),
            statusKey: api.seatStatusKey(s.status),
            key: s.seatCode
          }, s))
        } else {
          cells.push({ cellType: 'empty', key: r + '_' + c })
        }
      }
      rows.push({ row: r, cells: cells })
    }
    return rows
  },

  /* ---------------- 交互 ---------------- */
  switchRoom(e) {
    const key = e.currentTarget.dataset.key
    if (key === this.data.roomKey) return
    this.setData({ roomKey: key, selectedSeat: null })
    this.load()
  },

  manualRefresh() {
    this.load(true)
    util.toast('已刷新')
  },

  onSeatTap(e) {
    const seat = e.currentTarget.dataset.seat
    if (!seat) return
    if (seat.online === false) {
      util.toast('该座位设备离线，暂不可预约')
      return
    }
    if (seat.status !== 0) {
      util.toast('该座位当前为「' + api.seatStatusLabel(seat.status) + '」，请选择空闲座位')
      return
    }
    this.setData({ selectedSeat: seat })
  },

  /* ---------------- 确认预约 ---------------- */
  openConfirm() {
    if (!this.data.selectedSeat) {
      util.toast('请先在座位图中选择一个空闲座位')
      return
    }
    this.setData({ showConfirm: true })
  },

  closeConfirm() {
    this.setData({ showConfirm: false })
  },

  async doReserve() {
    if (this.data.confirming) return
    this.setData({ confirming: true })
    util.loading('正在提交预约')
    try {
      const r = await api.createReservation(this.data.selectedSeat.id)
      util.hideLoading()
      this.setData({ showConfirm: false, confirming: false })
      wx.redirectTo({ url: '/pages/reserve-success/index?id=' + r.id })
    } catch (err) {
      util.hideLoading()
      this.setData({ confirming: false, showConfirm: false })
      util.toast(err.message || '预约失败，请重试')
      this.load(true)
    }
  },

  back() {
    wx.navigateBack({ delta: 1 })
  },

  noop() {}
})
