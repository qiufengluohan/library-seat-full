import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getSeats } from '@/api/seat'
import { SEAT_STATUS, WS_MESSAGE_TYPE } from '@/constants/status'
import { TOKEN_KEY } from '@/utils/request'
import seatWebSocket from '@/utils/websocket'

/**
 * 全馆座位状态的唯一持有者。
 *
 * 数据来源只有两个：
 *   1. GET /api/admin/seats 的完整快照
 *   2. /ws/seats 推来的增量补丁，按 seat_id 定位后只改消息里带的字段
 *
 * 编码规范 §18：收到消息 -> 找 seat_id -> 更新该座位，不要重新自己计算业务状态。
 * 因此这里不存在任何根据 pressure_adc / pir_state 推断 status 的逻辑。
 */
export const useSeatStore = defineStore('seat', () => {
  /** @type {import('vue').Ref<Map<number, object>>} seat_id -> SeatVO */
  const seatMap = ref(new Map())
  const loading = ref(false)
  const loadError = ref('')
  const wsConnected = ref(false)
  const lastUpdated = ref(null)

  let unsubscribes = []
  let refetchScheduled = false

  const seats = computed(() =>
    [...seatMap.value.values()].sort((a, b) =>
      String(a.seat_code ?? '').localeCompare(String(b.seat_code ?? ''), 'zh-CN')
    )
  )

  const areas = computed(() => {
    const set = new Set()
    for (const seat of seatMap.value.values()) {
      if (seat.area) set.add(seat.area)
    }
    return [...set].sort((a, b) => String(a).localeCompare(String(b), 'zh-CN'))
  })

  const statusCounts = computed(() => {
    const counts = {
      [SEAT_STATUS.FREE]: 0,
      [SEAT_STATUS.RESERVED]: 0,
      [SEAT_STATUS.USING]: 0,
      [SEAT_STATUS.AWAY]: 0,
      [SEAT_STATUS.ALARM]: 0
    }
    for (const seat of seatMap.value.values()) {
      if (seat.status in counts) counts[seat.status] += 1
    }
    return counts
  })

  const onlineCount = computed(
    () => [...seatMap.value.values()].filter((seat) => seat.online === true).length
  )

  const offlineCount = computed(
    () => [...seatMap.value.values()].filter((seat) => seat.online !== true).length
  )

  const total = computed(() => seatMap.value.size)

  function seatsByArea(area) {
    return seats.value.filter((seat) => seat.area === area)
  }

  async function fetchSeats() {
    loading.value = true
    loadError.value = ''
    try {
      const list = await getSeats()
      const next = new Map()
      for (const seat of list ?? []) {
        next.set(seat.seat_id, seat)
      }
      seatMap.value = next
      lastUpdated.value = Date.now()
      return list
    } catch (error) {
      loadError.value = error.message || '加载座位失败'
      throw error
    } finally {
      loading.value = false
    }
  }

  /**
   * 只覆盖消息里确实带了的字段，避免用 undefined 抹掉已有状态。
   */
  function patchSeat(seatId, fields) {
    if (seatId === undefined || seatId === null) return

    const existing = seatMap.value.get(seatId)
    if (!existing) {
      // 收到未知座位的推送，说明本地快照已过期，重新拉一次
      scheduleRefetch()
      return
    }

    const changes = {}
    for (const [key, value] of Object.entries(fields)) {
      if (value !== undefined) changes[key] = value
    }
    if (Object.keys(changes).length === 0) return

    seatMap.value.set(seatId, { ...existing, ...changes })
    lastUpdated.value = Date.now()
  }

  function scheduleRefetch() {
    if (refetchScheduled) return
    refetchScheduled = true
    setTimeout(() => {
      refetchScheduled = false
      fetchSeats().catch(() => {})
    }, 500)
  }

  function bindSocketEvents() {
    unbindSocketEvents()

    unsubscribes = [
      seatWebSocket.on('open', () => {
        wsConnected.value = true
      }),
      // §38：重连成功后重新拉一次列表，补上断线期间漏掉的消息
      seatWebSocket.on('reconnected', () => {
        wsConnected.value = true
        fetchSeats().catch(() => {})
      }),
      seatWebSocket.on('close', () => {
        wsConnected.value = false
      }),
      seatWebSocket.on(WS_MESSAGE_TYPE.SEAT_UPDATE, (msg) => {
        patchSeat(msg.seat_id, {
          status: msg.status,
          alarm: msg.alarm,
          online: msg.online
        })
      }),
      seatWebSocket.on(WS_MESSAGE_TYPE.RESERVATION_UPDATE, (msg) => {
        patchSeat(msg.seat_id, { status: msg.status })
      }),
      // 告警消息只带 alarm 字段，业务 status 由后端另发 SEAT_UPDATE
      seatWebSocket.on(WS_MESSAGE_TYPE.ALARM, (msg) => {
        patchSeat(msg.seat_id, { alarm: msg.alarm })
      }),
      seatWebSocket.on(WS_MESSAGE_TYPE.DEVICE_STATUS, (msg) => {
        patchSeat(msg.seat_id, { online: msg.online })
      })
    ]
  }

  function unbindSocketEvents() {
    unsubscribes.forEach((off) => off())
    unsubscribes = []
  }

  function connect() {
    const token = localStorage.getItem(TOKEN_KEY)
    if (!token) return
    bindSocketEvents()
    seatWebSocket.connect(token)
  }

  function disconnect() {
    seatWebSocket.close()
    unbindSocketEvents()
    wsConnected.value = false
  }

  function reset() {
    disconnect()
    seatMap.value = new Map()
    loadError.value = ''
    lastUpdated.value = null
  }

  return {
    seatMap,
    seats,
    areas,
    statusCounts,
    onlineCount,
    offlineCount,
    total,
    loading,
    loadError,
    wsConnected,
    lastUpdated,
    seatsByArea,
    fetchSeats,
    connect,
    disconnect,
    reset
  }
})
