<script setup lang="ts">
import { computed, ref, onMounted, watch } from 'vue'
import { useUserStore } from '../stores/user'
import { useAppStore } from '../stores/app'
import { useExerciseStore } from '../stores/exercise'
import * as echarts from 'echarts'

const userStore = useUserStore()
const appStore = useAppStore()
const exerciseStore = useExerciseStore()

const calorieChartRef = ref<HTMLElement>()
const weekChartRef = ref<HTMLElement>()

// 初始化数据
onMounted(() => {
  userStore.initFromStorage()
  initCalorieChart()
  initWeekChart()
})

// 监听日期变化，重新加载图表
watch(() => appStore.selectedDate, () => {
  initCalorieChart()
  initWeekChart()
})

const goalLabels = {
  fat_loss: '减脂',
  maintain: '保持',
  muscle_gain: '增肌'
}

const goalColors = {
  fat_loss: '#67c23a',
  maintain: '#409eff',
  muscle_gain: '#f56c6c'
}

const progressPercent = computed(() => {
  const { foodIntake, targetCalories, exerciseBurn } = userStore.todayRecord
  const consumed = foodIntake - exerciseBurn
  return Math.min(100, Math.round((consumed / targetCalories) * 100))
})

const remainingCalories = computed(() => {
  return userStore.todayRecord.targetCalories - userStore.todayRecord.foodIntake + userStore.todayRecord.exerciseBurn
})

// 根据选择的日期获取记录
const selectedDateRecord = computed(() => {
  const record = userStore.dailyRecords.find(r => r.date === appStore.selectedDate)
  return record || {
    date: appStore.selectedDate,
    foodIntake: 0,
    exerciseBurn: 0,
    targetCalories: userStore.targetCalories
  }
})

const initCalorieChart = () => {
  if (!calorieChartRef.value) return
  const chart = echarts.init(calorieChartRef.value)
  
  // 使用选择日期的数据
  const record = selectedDateRecord.value
  const remaining = record.targetCalories - record.foodIntake + record.exerciseBurn
  
  const option = {
    tooltip: {
      trigger: 'item',
      formatter: '{b}: {c} 千卡 ({d}%)'
    },
    legend: {
      orient: 'vertical',
      left: 'left'
    },
    series: [
      {
        name: '今日摄入',
        type: 'pie',
        radius: ['40%', '70%'],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 10,
          borderColor: '#fff',
          borderWidth: 2
        },
        label: {
          show: true,
          formatter: '{b}: {c}kcal'
        },
        data: [
          { value: record.foodIntake, name: '已摄入', itemStyle: { color: '#409eff' } },
          { value: Math.max(0, remaining), name: '剩余', itemStyle: { color: '#e6e6e6' } }
        ]
      }
    ]
  }
  chart.setOption(option)
}

const initWeekChart = () => {
  if (!weekChartRef.value) return
  const chart = echarts.init(weekChartRef.value)
  
  // 获取本周热量缺口数据
  const weekData = userStore.getWeeklyDeficit()
  
  const option = {
    tooltip: {
      trigger: 'axis',
      formatter: (params: any) => {
        const data = params[0]
        const value = data.value
        const status = value > 0 ? '还需消耗' : value < 0 ? '已超出' : '已达成'
        return `${data.name}<br/>热量缺口: ${Math.abs(value)} 千卡 (${status})`
      }
    },
    xAxis: {
      type: 'category',
      data: weekData.map(d => d.date.slice(5)),
      axisLabel: {
        formatter: (val: string) => val.replace('-', '/')
      }
    },
    yAxis: {
      type: 'value',
      name: '千卡'
    },
    series: [
      {
        name: '热量缺口',
        type: 'bar',
        data: weekData.map(d => ({
          value: d.deficit,
          itemStyle: {
            color: d.deficit > 0 ? '#67c23a' : d.deficit < 0 ? '#f56c6c' : '#409eff',
            borderRadius: [4, 4, 0, 0]
          }
        })),
        label: {
          show: true,
          position: 'top',
          formatter: (params: any) => {
            const value = params.value
            return value > 0 ? `${value}` : value < 0 ? `${Math.abs(value)}` : '0'
          }
        }
      }
    ],
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      containLabel: true
    }
  }
  chart.setOption(option)
}

onMounted(() => {
  initCalorieChart()
  initWeekChart()
})
</script>

<template>
  <div class="dashboard">
    <el-row :gutter="20">
      <el-col :span="16">
        <el-row :gutter="20" class="stats-row">
          <el-col :span="8">
            <el-card shadow="hover" class="stat-card">
              <div class="stat-header">
                <span class="stat-title">今日目标</span>
                <el-tag :color="goalColors[userStore.profile.goal]" size="small">
                  {{ goalLabels[userStore.profile.goal] }}
                </el-tag>
              </div>
              <div class="stat-value">{{ userStore.targetCalories }}<span class="unit">千卡</span></div>
              <div class="stat-desc">每日建议摄入</div>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card shadow="hover" class="stat-card">
              <div class="stat-header">
                <span class="stat-title">已摄入</span>
                <el-icon class="stat-icon" color="#409eff"><Food /></el-icon>
              </div>
              <div class="stat-value" style="color: #409eff">{{ userStore.todayRecord.foodIntake }}<span class="unit">千卡</span></div>
              <div class="stat-desc">今日食物摄入</div>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card shadow="hover" class="stat-card">
              <div class="stat-header">
                <span class="stat-title">已消耗</span>
                <el-icon class="stat-icon" color="#f56c6c"><Coin /></el-icon>
              </div>
              <div class="stat-value" style="color: #f56c6c">{{ userStore.todayRecord.exerciseBurn }}<span class="unit">千卡</span></div>
              <div class="stat-desc">今日运动消耗</div>
            </el-card>
          </el-col>
        </el-row>

        <el-card shadow="hover" class="chart-card">
          <template #header>
            <div class="card-header">
              <span>今日热量分布</span>
              <el-progress :percentage="progressPercent" :color="progressPercent > 100 ? '#f56c6c' : '#409eff'" />
            </div>
          </template>
          <div ref="calorieChartRef" class="chart-container"></div>
        </el-card>

        <el-card shadow="hover" class="chart-card">
          <template #header>
            <div class="card-header">
              <span>本周热量缺口</span>
              <el-tag type="info" size="small">
                目标: {{ userStore.targetCalories }} 千卡/天
              </el-tag>
            </div>
          </template>
          <div ref="weekChartRef" class="chart-container"></div>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card shadow="hover" class="nutrition-card">
          <template #header>
            <span>今日营养目标</span>
          </template>
          <div class="nutrition-item">
            <div class="nutrition-label">蛋白质</div>
            <el-progress :percentage="30" color="#67c23a" :show-text="false" />
            <div class="nutrition-value">{{ userStore.recommendedProtein }}g</div>
          </div>
          <div class="nutrition-item">
            <div class="nutrition-label">碳水化合物</div>
            <el-progress :percentage="50" color="#e6a23c" :show-text="false" />
            <div class="nutrition-value">{{ userStore.recommendedCarbs }}g</div>
          </div>
          <div class="nutrition-item">
            <div class="nutrition-label">脂肪</div>
            <el-progress :percentage="20" color="#909399" :show-text="false" />
            <div class="nutrition-value">{{ userStore.recommendedFat }}g</div>
          </div>
        </el-card>

        <el-card shadow="hover" class="deficit-card">
          <template #header>
            <span>卡路里缺口</span>
          </template>
          <div class="deficit-main">
            <div class="deficit-value" :style="{ color: remainingCalories > 0 ? '#67c23a' : '#f56c6c' }">
              {{ remainingCalories }}
            </div>
            <div class="deficit-unit">千卡</div>
          </div>
          <div class="deficit-hint">
            <span v-if="remainingCalories > 0">还需消耗 {{ remainingCalories }} 千卡</span>
            <span v-else-if="remainingCalories < 0">已超出 {{ Math.abs(remainingCalories) }} 千卡</span>
            <span v-else>今日目标已达成!</span>
          </div>
        </el-card>

        <el-card shadow="hover" class="bmi-card">
          <template #header>
            <span>身体质量指数</span>
          </template>
          <div class="bmi-display">
            <div class="bmi-value">{{ userStore.bmi.toFixed(1) }}</div>
            <div class="bmi-label">
              <el-tag v-if="userStore.bmi < 18.5" type="info" size="small">偏瘦</el-tag>
              <el-tag v-else-if="userStore.bmi < 24" type="success" size="small">正常</el-tag>
              <el-tag v-else-if="userStore.bmi < 28" type="warning" size="small">超重</el-tag>
              <el-tag v-else type="danger" size="small">肥胖</el-tag>
            </div>
          </div>
          <div class="bmi-range">
            <span>偏瘦 &lt;18.5</span>
            <span>正常 18.5-24</span>
            <span>超重 &gt;24</span>
          </div>
        </el-card>

        <el-card shadow="hover">
          <template #header>
            <span>今日运动</span>
          </template>
          <div v-if="exerciseStore.todayWorkouts.length === 0" class="empty-workout">
            <el-icon size="40" color="#c0c4cc"><Coin /></el-icon>
            <p>今日暂无运动记录</p>
            <el-button type="primary" size="small" @click="$router.push('/exercise')">添加运动</el-button>
          </div>
          <div v-else class="workout-list">
            <div v-for="workout in exerciseStore.todayWorkouts" :key="workout.date + workout.exerciseId" class="workout-item">
              <span>{{ exerciseStore.getExerciseById(workout.exerciseId)?.name }}</span>
              <span>{{ workout.duration }}分钟 / {{ workout.caloriesBurned }}千卡</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.dashboard {
  padding: 0;
}

.stats-row {
  margin-bottom: 20px;
}

.stat-card {
  border-radius: 12px;
}

.stat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.stat-title {
  color: #909399;
  font-size: 14px;
}

.stat-icon {
  font-size: 20px;
}

.stat-value {
  font-size: 32px;
  font-weight: bold;
  color: #303133;
}

.stat-value .unit {
  font-size: 14px;
  color: #909399;
  margin-left: 4px;
}

.stat-desc {
  color: #c0c4cc;
  font-size: 12px;
  margin-top: 5px;
}

.chart-card {
  margin-bottom: 20px;
  border-radius: 12px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chart-container {
  width: 100%;
  height: 280px;
}

.nutrition-card {
  border-radius: 12px;
  margin-bottom: 20px;
}

.nutrition-item {
  margin-bottom: 15px;
}

.nutrition-label {
  font-size: 14px;
  color: #606266;
  margin-bottom: 8px;
}

.nutrition-value {
  font-size: 14px;
  font-weight: bold;
  margin-top: 5px;
}

.deficit-card {
  border-radius: 12px;
  margin-bottom: 20px;
  text-align: center;
}

.deficit-main {
  padding: 20px 0;
}

.deficit-value {
  font-size: 48px;
  font-weight: bold;
}

.deficit-unit {
  font-size: 16px;
  color: #909399;
}

.deficit-hint {
  color: #606266;
  font-size: 14px;
}

.bmi-card {
  border-radius: 12px;
  margin-bottom: 20px;
}

.bmi-display {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 15px;
}

.bmi-value {
  font-size: 36px;
  font-weight: bold;
  color: #303133;
}

.bmi-range {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #c0c4cc;
}

.empty-workout {
  text-align: center;
  padding: 20px;
  color: #c0c4cc;
}

.empty-workout p {
  margin: 10px 0;
}

.workout-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.workout-item {
  display: flex;
  justify-content: space-between;
  padding: 10px;
  background: #f5f7fa;
  border-radius: 8px;
  font-size: 14px;
}
</style>