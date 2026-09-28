/**
 * 意见反馈（静态表单）
 * ------------------------------------------------------------
 * 本页按需求做成静态界面：表单交互完整可用，但不接入任何数据层，
 * 提交后只做本地校验与结果提示。
 * 真实的违规与告警数据由设备与云端产生（规范第四十四节的 FAKE_OCCUPY /
 * RESERVATION_TIMEOUT / AWAY_TIMEOUT），在 Web 管理端的违规管理中查看。
 */
const app = getApp()
const util = require('../../utils/util')
const config = require('../../utils/config')

Page({
  data: {
    statusBarHeight: 44,
    seatInfo: '',
    types: [
      { key: 'occupy', label: '座位被占' },
      { key: 'device', label: '设备故障' },
      { key: 'reserve', label: '预约异常' },
      { key: 'clean', label: '环境卫生' },
      { key: 'advice', label: '意见建议' },
      { key: 'other', label: '其他' }
    ],
    type: 'occupy',
    content: '',
    images: [],
    contact: '',
    submitting: false,
    tel: config.business.serviceTel
  },

  onLoad(options) {
    this.setData({
      statusBarHeight: (app.globalData && app.globalData.statusBarHeight) || 44,
      seatInfo: options && options.seatInfo ? decodeURIComponent(options.seatInfo) : ''
    })
  },

  onPickType(e) {
    this.setData({ type: e.currentTarget.dataset.key })
  },

  onContent(e) {
    this.setData({ content: e.detail.value })
  },

  onContact(e) {
    this.setData({ contact: e.detail.value })
  },

  /* ---------------- 图片 ---------------- */
  chooseImage() {
    const rest = 3 - this.data.images.length
    if (rest <= 0) return
    wx.chooseMedia({
      count: rest,
      mediaType: ['image'],
      sizeType: ['compressed'],
      success: res => {
        const paths = (res.tempFiles || []).map(f => f.tempFilePath)
        this.setData({ images: this.data.images.concat(paths) })
      },
      fail: () => {}
    })
  },

  previewImage(e) {
    const index = e.currentTarget.dataset.index
    wx.previewImage({ current: this.data.images[index], urls: this.data.images })
  },

  removeImage(e) {
    const index = e.currentTarget.dataset.index
    const images = this.data.images.slice()
    images.splice(index, 1)
    this.setData({ images: images })
  },

  /* ---------------- 提交 ---------------- */
  onSubmit() {
    const content = (this.data.content || '').trim()
    if (!content) {
      util.toast('请填写问题描述')
      return
    }
    if (this.data.submitting) return
    this.setData({ submitting: true })
    wx.showModal({
      title: '提交成功',
      content: '我们已收到你的反馈，工作人员会在 1 个工作日内处理。',
      showCancel: false,
      confirmColor: '#3A6BF0',
      success: () => {
        this.setData({ submitting: false })
        setTimeout(() => wx.navigateBack({ delta: 1 }), 200)
      }
    })
  },

  callService() {
    wx.makePhoneCall({ phoneNumber: this.data.tel, fail: () => {} })
  },

  back() {
    wx.navigateBack({ delta: 1 })
  }
})
