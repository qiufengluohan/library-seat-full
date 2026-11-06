<template>
  <div class="seat-grid">
    <template v-if="grouped.length">
      <div v-for="group in grouped" :key="group.area" class="grid-group">
        <div v-if="showAreaTitle" class="group-title">{{ group.area }}</div>
        <div class="grid-body">
          <SeatCard
            v-for="seat in group.seats"
            :key="seat.seat_id"
            :seat="seat"
            @click="$emit('select', seat)"
          />
        </div>
      </div>
    </template>

    <el-empty v-else description="暂无座位数据" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import SeatCard from './SeatCard.vue'

const props = defineProps({
  seats: { type: Array, default: () => [] },
  // 关掉分组时按纯网格平铺，用于 Dashboard 的小尺寸概览
  showAreaTitle: { type: Boolean, default: true }
})

defineEmits(['select'])

const grouped = computed(() => {
  const map = new Map()
  for (const seat of props.seats) {
    const area = seat.area || '未分区'
    if (!map.has(area)) map.set(area, [])
    map.get(area).push(seat)
  }
  return [...map.entries()]
    .sort((a, b) => String(a[0]).localeCompare(String(b[0]), 'zh-CN'))
    .map(([area, seats]) => ({ area, seats }))
})
</script>

<style scoped lang="scss">
.grid-group {
  & + & {
    margin-top: 18px;
  }

  .group-title {
    font-size: 15px;
    font-weight: 700;
    color: #303133;
    margin-bottom: 10px;
  }

  .grid-body {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(88px, 1fr));
    gap: 10px;
  }
}
</style>
