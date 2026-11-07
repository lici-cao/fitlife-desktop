<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useExerciseStore } from '../stores/exercise'
import { useUserStore } from '../stores/user'
import { useAppStore } from '../stores/app'
import { ElMessage } from 'element-plus'

const exerciseStore = useExerciseStore()
const userStore = useUserStore()
const appStore = useAppStore()

// 初始化数据
onMounted(() => {
  userStore.initFromStorage()
})

const selectedExercise = ref<string>('')
const duration = ref(30)
const dialogVisible = ref(false)

// 根据选择的日期获取消耗数据
const todayBurn = computed(() => {
  const record = userStore.dailyRecords.find(r => r.date === appStore.selectedDate)
  return record?.exerciseBurn || 0
})

const todayWorkouts = computed(() => {
  return exerciseStore.todayWorkouts.map(w => ({
    ...w,
    exercise: exerciseStore.getExerciseById(w.exerciseId)
  }))
})

const filteredExercises = computed(() => {
  if (!selectedExercise.value) return exerciseStore.exerciseDatabase
  return exerciseStore.exerciseDatabase.filter(e => e.category === selectedExercise.value)
})

const estimatedCalories = computed(() => {
  if (!selectedExercise.value) return 0
  return exerciseStore.calculateCalories(selectedExercise.value, duration.value)
})

const categoryOptions = [
  { label: '全部', value: '' },
  { label: '有氧运动', value: 'cardio' },
  { label: '力量训练', value: 'strength' },
  { label: '柔韧性', value: 'flexibility' },
  { label: '球类运动', value: 'sports' }
]

const difficultyColors: Record<string, string> = {
  easy: '#67c23a',
  medium: '#e6a23c',
  hard: '#f56c6c'
}

const difficultyLabels: Record<string, string> = {
  easy: '简单',
  medium: '中等',
  hard: '困难'
}

const categoryLabels: Record<string, string> = {
  cardio: '有氧',
  strength: '力量',
  flexibility: '柔韧',
  sports: '球类'
}

const addWorkout = () => {
  if (!selectedExercise.value) {
    ElMessage.warning('请选择运动项目')
    return
  }
  exerciseStore.addWorkout(selectedExercise.value, duration.value, appStore.selectedDate)
  userStore.addExerciseBurn(estimatedCalories.value, appStore.selectedDate)
  ElMessage.success(`已添加运动记录: 消耗 ${estimatedCalories.value} 千卡`)
  selectedExercise.value = ''
  duration.value = 30
}
</script>

<template>
  <div class="exercise-page">
    <el-row :gutter="20">
      <el-col :span="16">
        <el-card shadow="hover" class="exercise-card">
          <template #header>
            <span>运动项目</span>
          </template>
          <div class="filter-row">
            <el-radio-group v-model="selectedExercise" size="large">
              <el-radio-button v-for="opt in categoryOptions" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </el-radio-button>
            </el-radio-group>
          </div>

          <el-table :data="filteredExercises" style="width: 100%; margin-top: 20px" max-height="400">
            <el-table-column prop="name" label="运动名称" width="120" />
            <el-table-column label="类型" width="100">
              <template #default="{ row }">
                <el-tag size="small">{{ categoryLabels[row.category] }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="难度" width="100">
              <template #default="{ row }">
                <el-tag :color="difficultyColors[row.difficulty]" size="small" style="color: #fff">
                  {{ difficultyLabels[row.difficulty] }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="每小时消耗" width="120">
              <template #default="{ row }">
                <span class="calorie-highlight">{{ row.caloriesPerHour }}</span> 千卡
              </template>
            </el-table-column>
            <el-table-column prop="description" label="描述" />
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" size="small" @click="selectedExercise = row.id; dialogVisible = true">
                  记录
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card shadow="hover" class="today-summary">
          <template #header>
            <span>今日运动消耗</span>
          </template>
          <div class="summary-main">
            <div class="summary-value">{{ todayBurn }}</div>
            <div class="summary-unit">千卡</div>
          </div>
          <div class="summary-progress">
            <el-progress
              :percentage="Math.min(100, Math.round((todayBurn / 500) * 100))"
              color="#67c23a"
            />
          </div>
          <div class="summary-hint">
            推荐每日运动消耗 300-500 千卡
          </div>
        </el-card>

        <el-card shadow="hover" class="workout-list-card">
          <template #header>
            <span>今日运动记录</span>
          </template>
          <div v-if="todayWorkouts.length === 0" class="empty-state">
            <el-icon size="50" color="#c0c4cc"><Coin /></el-icon>
            <p>暂无运动记录</p>
            <p class="hint">开始记录你的运动吧!</p>
          </div>
          <div v-else class="workout-list">
            <div v-for="(workout, index) in todayWorkouts" :key="index" class="workout-item">
              <div class="workout-info">
                <div class="workout-name">{{ workout.exercise?.name }}</div>
                <div class="workout-duration">{{ workout.duration }} 分钟</div>
              </div>
              <div class="workout-calories">
                {{ workout.caloriesBurned }} 千卡
              </div>
            </div>
          </div>
        </el-card>

        <el-card shadow="hover" class="tips-card">
          <template #header>
            <span>运动建议</span>
          </template>
          <el-timeline>
            <el-timeline-item timestamp="早晨" placement="top">
              空腹有氧训练效果最佳，如快走、慢跑
            </el-timeline-item>
            <el-timeline-item timestamp="下午" placement="top">
              力量训练的最佳时间，肌肉力量达到峰值
            </el-timeline-item>
            <el-timeline-item timestamp="晚上" placement="top">
              可进行柔韧性训练或轻度运动
            </el-timeline-item>
          </el-timeline>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="dialogVisible" title="记录运动" width="400px">
      <div class="workout-form">
        <div class="form-item">
          <label>运动项目</label>
          <div class="exercise-name">
            {{ exerciseStore.getExerciseById(selectedExercise)?.name }}
          </div>
        </div>
        <div class="form-item">
          <label>运动时长</label>
          <el-slider v-model="duration" :min="5" :max="180" :step="5" :show-input="true" />
          <span class="duration-label">{{ duration }} 分钟</span>
        </div>
        <div class="form-item">
          <label>预计消耗</label>
          <div class="calorie-estimate">{{ estimatedCalories }} 千卡</div>
        </div>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="addWorkout(); dialogVisible = false">确认添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.exercise-page {
  padding: 0;
}

.exercise-card {
  border-radius: 12px;
}

.filter-row {
  margin-bottom: 10px;
}

.calorie-highlight {
  color: #f56c6c;
  font-weight: bold;
}

.today-summary {
  border-radius: 12px;
  margin-bottom: 20px;
  text-align: center;
}

.summary-main {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 5px;
  margin-bottom: 20px;
}

.summary-value {
  font-size: 48px;
  font-weight: bold;
  color: #67c23a;
}

.summary-unit {
  color: #909399;
}

.summary-progress {
  margin-bottom: 10px;
}

.summary-hint {
  color: #909399;
  font-size: 14px;
}

.workout-list-card {
  border-radius: 12px;
  margin-bottom: 20px;
}

.empty-state {
  text-align: center;
  padding: 30px;
  color: #c0c4cc;
}

.empty-state .hint {
  font-size: 12px;
}

.workout-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.workout-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 15px;
  background: #f5f7fa;
  border-radius: 8px;
}

.workout-name {
  font-weight: bold;
  color: #303133;
}

.workout-duration {
  font-size: 12px;
  color: #909399;
  margin-top: 5px;
}

.workout-calories {
  color: #67c23a;
  font-weight: bold;
}

.tips-card {
  border-radius: 12px;
}

.workout-form {
  padding: 20px 0;
}

.form-item {
  margin-bottom: 25px;
}

.form-item label {
  display: block;
  margin-bottom: 10px;
  color: #606266;
}

.exercise-name {
  font-size: 18px;
  font-weight: bold;
  color: #303133;
}

.duration-label {
  display: block;
  text-align: center;
  margin-top: 10px;
  color: #409eff;
  font-weight: bold;
}

.calorie-estimate {
  font-size: 32px;
  font-weight: bold;
  color: #f56c6c;
  text-align: center;
}
</style>