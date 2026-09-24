<template>
  <el-card shadow="hover" class="stat-card">
    <div class="stat-content">
      <el-icon v-if="icon" :size="38" :color="color">
        <component :is="icon" />
      </el-icon>
      <div class="stat-info">
        <div class="stat-value" :style="{ color }">
          {{ value ?? 0 }}<span v-if="suffix" class="stat-suffix">{{ suffix }}</span>
        </div>
        <div class="stat-label">{{ label }}</div>
      </div>
    </div>
  </el-card>
</template>

<script setup>
defineProps({
  label: { type: String, required: true },
  value: { type: [Number, String], default: 0 },
  icon: { type: String, default: '' },
  color: { type: String, default: '#409EFF' },
  suffix: { type: String, default: '' }
})
</script>

<style scoped lang="scss">
.stat-card {
  .stat-content {
    display: flex;
    align-items: center;
    gap: 16px;
  }

  .stat-info {
    flex: 1;
    min-width: 0;
  }

  .stat-value {
    font-size: 26px;
    font-weight: 700;
    line-height: 1.2;
  }

  .stat-suffix {
    font-size: 13px;
    font-weight: 400;
    margin-left: 3px;
  }

  .stat-label {
    font-size: 13px;
    color: #909399;
    margin-top: 4px;
  }
}
</style>
