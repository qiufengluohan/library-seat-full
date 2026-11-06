<template>
  <div
    class="seat-card"
    :class="{ 'is-alarm': inAlarm, 'is-offline': isOffline }"
    :style="cardStyle"
    @click="$emit('click', seat)"
  >
    <!-- 离线是叠加标记，不覆盖业务状态（编码规范 §39） -->
    <span v-if="isOffline" class="offline-badge">离线</span>

    <div class="seat-code">{{ seat.seat_code || `#${seat.seat_id}` }}</div>
    <div class="seat-status">{{ seatStatusLabel(seat.status) }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { SEAT_STATUS, seatStatusLabel, seatStatusColor } from '@/constants/status'

const props = defineProps({
  seat: { type: Object, required: true }
})

defineEmits(['click'])

// 告警可能由 status=4 表达，也可能只通过 ALARM 消息的 alarm 字段表达
const inAlarm = computed(
  () => props.seat.status === SEAT_STATUS.ALARM || props.seat.alarm === true
)

const isOffline = computed(() => props.seat.online !== true)

const cardStyle = computed(() => {
  const color = seatStatusColor(props.seat.status)
  return {
    borderColor: color,
    color,
    backgroundColor: `${color}14`
  }
})
</script>

<style scoped lang="scss">
.seat-card {
  position: relative;
  padding: 10px 8px;
  border: 2px solid #c0c4cc;
  border-radius: 6px;
  text-align: center;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
  user-select: none;

  &:hover {
    transform: translateY(-2px);
    box-shadow: 0 3px 10px rgba(0, 0, 0, 0.14);
  }

  .seat-code {
    font-size: 14px;
    font-weight: 700;
    line-height: 1.3;
  }

  .seat-status {
    font-size: 12px;
    color: #909399;
    margin-top: 4px;
  }

  .offline-badge {
    position: absolute;
    top: -8px;
    right: -6px;
    padding: 1px 5px;
    font-size: 10px;
    line-height: 1.5;
    color: #fff;
    background-color: #909399;
    border-radius: 8px;
  }

  &.is-offline {
    opacity: 0.62;
  }

  &.is-alarm {
    animation: seat-blink 1s infinite;
  }
}

@keyframes seat-blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}
</style>
