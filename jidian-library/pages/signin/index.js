/**
 * 签到页 —— 按《实际编码规范》第六节的状态机
 * ------------------------------------------------------------
 *   1. 「RFID 签到」不是学生端手动调用的：
 *      读卡器 → OneNET → Spring Boot → RFIDService.signIn()
 *      所以本页不提供「签到」按钮，只展示「等待读卡器」与签到结果；
 *   2. 学生端只负责三个业务动作：暂离 / 回座 / 离座。
 *      USING → 暂离 → AWAY → 回座 → USING，USING → 离座 → COMPLETED → 座位 FREE
 *
 * 状态与时间全部来自后端（GET /api/reservations/{id}）。
 * 待签到期间每 3 秒向后端确认一次，读卡器上报后端后本页会自动切到「使用中」。
 */
const app = getApp()
const api = require('../../utils/api')
const util = require('../../utils/util')
const config = require('../../utils/config')

Page({
  data: {
    statusBarHeight: 44,
    id: 0,
    r: null,
    statusLabel: '',
    statusTag: 'tag-grey',
    color: '#3A6BF0',
    counterLabel: '',
    counterValue: '--:--',
    busy: false,
    loading: true,
    error: '',
    /** 本人校园卡号（来自 rfid_user 表，未绑定显示「暂无」） */
    rfidUid: '',

    reserveExpireMin: config.business.reserveExpireMin,
    awayExpireMin: config.business.awayExpireMin
  },

  onLoad(options) {
    this.setData({
      statusBarHeight: (app.globalData && app.globalData.statusBarHeight) || 44,
      id: options.id || 0,
      rfidUid: (app.globalData.userInfo && app.globalData.userInfo.rfidUid) || ''
    })
    this.load()
    this.loadCard()
  },

  /** 校园卡号以接口为准（我的页可能还没访问过） */
  loadCard() {
    if (!api.isConfigured()) return
    api.getUserProfile().then(u => {
      this.setData({ rfidUid: (u && u.rfidUid) || '' })
    }).catch(() => {})
  },

  onShow() {
    this.startTimer()
  },

  onHide() {
    this.stopTimer()
  },

  onUnload() {
    this.stopTimer()
  },

  /* ============================================================
   * 数据
   * ========================================================== */
  async load(silent) {
    if (!this.data.id) {
      this.setData({ loading: false, error: '缺少预约编号' })
      return
    }
    if (!api.isConfigured()) {
      this.setData({ loading: false, error: '尚未配置后端地址：请在 utils/config.js 里填写 server.baseUrl' })
      return
    }
    try {
      const r = await api.getReservationDetail(this.data.id)
      if (!r) {
        this.setData({ loading: false, error: '预约记录不存在' })
        return
      }
      this.apply(r)
    } catch (e) {
      if (!silent) this.setData({ loading: false, error: e.message || '加载失败' })
    }
  },

  apply(r) {
    this.setData({
      r: r,
      statusLabel: api.reserveStatusLabel(r.status),
      statusTag: api.reserveStatusTag(r.status),
      color: api.seatStatusColor(
        r.status === 'RESERVED' ? 1 : (r.status === 'USING' ? 2 : (r.status === 'AWAY' ? 3 : 0))
      ),
      loading: false,
      error: ''
    })
    this.tick()
  },

  /** 每秒刷新的计时区 */
  tick() {
    const r = this.data.r
    if (!r) return
    const now = Date.now()

    if (r.status === 'RESERVED') {
      this.setData({
        counterLabel: '距离自动释放',
        counterValue: util.countdownText(util.toTimestamp(r.reserveExpireAt) - now)
      })
    } else if (r.status === 'AWAY') {
      this.setData({
        counterLabel: '暂离剩余时间',
        counterValue: util.countdownText(util.toTimestamp(r.leaveExpireAt) - now)
      })
    } else if (r.status === 'USING') {
      this.setData({
        counterLabel: '已学习时长',
        counterValue: util.minutesText(util.diffMinutes(r.signTime, util.nowText()))
      })
    } else {
      this.setData({
        counterLabel: '本次学习时长',
        counterValue: r.signTime && r.releaseTime
          ? util.minutesText(util.diffMinutes(r.signTime, r.releaseTime))
          : '--:--'
      })
    }
  },

  startTimer() {
    this.stopTimer()
    this.timer = setInterval(() => {
      this.tick()
      // 待签到期间等读卡器上报：每 3 秒向后端确认一次状态
      if (this.data.r && this.data.r.status === 'RESERVED') {
        this.pollTick = (this.pollTick || 0) + 1
        if (this.pollTick >= 3) {
          this.pollTick = 0
          this.load(true)
        }
      }
    }, 1000)
  },

  stopTimer() {
    if (this.timer) {
      clearInterval(this.timer)
      this.timer = null
    }
    this.pollTick = 0
  },

  onRefresh() {
    this.load(true)
    util.toast('已刷新')
  },

  /* ============================================================
   * 业务动作：暂离 / 回座 / 离座（规范 23 节）
   * ========================================================== */
  async onLeave() {
    if (this.data.busy) return
    const ok = await util.confirm(
      '暂离后座位为你保留 ' + this.data.awayExpireMin + ' 分钟，超时未回座将自动释放。',
      '确认暂离？'
    )
    if (!ok) return
    this.submit('leaveSeat', '已进入暂离状态')
  },

  async onReturn() {
    if (this.data.busy) return
    this.submit('returnSeat', '欢迎回来，已恢复使用')
  },

  async onRelease() {
    if (this.data.busy) return
    const ok = await util.confirm('离座后本次使用结束，座位将立即释放给其他同学。', '确认离座？')
    if (!ok) return
    this.submit('releaseSeat', '已离座，座位已释放')
  },

  /** RESERVED → CANCELLED（规范 14.4：只有待签到的预约可以取消） */
  async onCancel() {
    if (this.data.busy) return
    const ok = await util.confirm('取消后该座位将立即释放，确定取消本次预约吗？', '确认取消？')
    if (!ok) return
    this.submit('cancelReservation', '已取消预约')
  },

  async submit(action, successText) {
    this.setData({ busy: true })
    util.loading('处理中')
    try {
      const r = await api[action](this.data.id)
      util.hideLoading()
      this.setData({ busy: false })
      if (r) this.apply(r)
      util.toast(successText)
    } catch (e) {
      util.hideLoading()
      this.setData({ busy: false })
      util.toast(e.message || '操作失败，请重试')
      this.load(true)
    }
  },

  /* ---------------- 跳转 ---------------- */
  goSeats() {
    wx.redirectTo({ url: '/pages/seats/index' })
  },

  goMyReserve() {
    wx.switchTab({ url: '/pages/my-reserve/index' })
  },

  back() {
    wx.navigateBack({ delta: 1 })
  }
})
