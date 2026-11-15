<script setup lang="ts">
import { computed, ref, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'
import { useAppStore } from '../stores/app'
import { useExerciseStore } from '../stores/exercise'
import { statsApi, todayPlanApi } from '../api'
import type { StatsSummary, ProgressAdvice, TodayPlan } from '../types/stats'
import * as echarts from 'echarts'

const userStore = useUserStore()
const appStore = useAppStore()
const exerciseStore = useExerciseStore()

const calorieChartRef = ref<HTMLElement>()
const weekChartRef = ref<HTMLElement>()

// P4：统计数据一律取服务端聚合结果，前端不再自算（G6）
const stats = ref<StatsSummary | null>(null)
const advice = ref<ProgressAdvice | null>(null)
const todayPlan = ref<TodayPlan | null>(null)
const serverError = ref('')
const loading = ref(false)

const loadServerStats = async () => {
  loading.value = true
  serverError.value = ''
  try {
    const [summaryRes, adviceRes, todayRes] = await Promise.all([
      statsApi.summary({ days: 7 }),
      statsApi.advice({ days: 7 }),
      todayPlanApi.get()
    ])
    stats.value = summaryRes.data.data
    advice.value = adviceRes.data.data
    todayPlan.value = todayRes.data.data
    // 用服务端口径覆盖本地画像中的目标热量，避免前后端口径不一致
    if (stats.value?.avgNetCalories !== undefined) {
      userStore.syncTargetCalories(stats.value.targetCalories)
    }
  } catch (e: any) {
    serverError.value = e?.response?.data?.message || '统计数据加载失败'
  } finally {
    loading.value = false
  }
}

// 初始化数据
onMounted(async () => {
  userStore.initFromStorage()
  await loadServerStats()
  initCalorieChart()
  initWeekChart()
})

// 监听日期变化，重新加载图表
watch(() => appStore.selectedDate, async () => {
  await loadServerStats()
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
  const record = selectedDateRecord.value
  const target = record.targetCalories || userStore.targetCalories || 1
  const consumed = record.foodIntake - record.exerciseBurn
  return Math.min(100, Math.max(0, Math.round((consumed / target) * 100)))
})
// 热量收支卡不再显示进度环，保留计算供「卡路里缺口」卡使用
void progressPercent

const remainingCalories = computed(() => {
  const record = selectedDateRecord.value
  return Math.round(record.targetCalories - record.foodIntake + record.exerciseBurn)
})

const seriesOf = (date: string) => stats.value?.series?.find(s => s.date === date)

const adherenceColor = computed(() => {
  const rate = stats.value?.adherenceRate ?? 0
  if (rate >= 90) return '#67c23a'
  if (rate >= 70) return '#e6a23c'
  return '#f56c6c'
})

const rpeDeviationText = computed(() => {
  const d = stats.value?.rpeDeviation
  if (d === null || d === undefined) return '—'
  return `${d > 0 ? '+' : ''}${d}`
})

const riskTagType = (level: string) => {
  if (level === 'DANGER') return 'danger'
  if (level === 'WARN') return 'warning'
  return 'info'
}

const riskLevelLabel = (level: string) => {
  if (level === 'DANGER') return '高'
  if (level === 'WARN') return '中'
  return '提示'
}

const adviceTagType = computed(() => {
  switch (advice.value?.decision) {
    case 'PROGRESS':
      return 'success'
    case 'DELOAD':
      return 'warning'
    case 'STOP':
      return 'danger'
    default:
      return 'info'
  }
})

const advancing = ref(false)

// 按建议进阶：生成下一版计划（parent 指向当前版，服务端复核 ≤10% 增幅）
const applyAdvice = async () => {
  advancing.value = true
  try {
    const res = await statsApi.advance({ days: 7 })
    const data = res.data.data
    ElMessage.success(data?.note || '已生成下一版计划')
    await loadServerStats()
    initWeekChart()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '生成失败')
  } finally {
    advancing.value = false
  }
}

// 根据选择的日期获取记录：优先服务端序列，本地仅作兜底
const selectedDateRecord = computed(() => {
  const s = seriesOf(appStore.selectedDate)
  if (s) {
    return {
      date: s.date,
      foodIntake: s.intake || 0,
      exerciseBurn: s.burn || 0,
      targetCalories: stats.value?.targetCalories || userStore.targetCalories
    }
  }
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
  
  // P4：热量收支按「摄入 / 消耗 / 净摄入」三条呈现，数据全部来自服务端 series（不再读 localStorage）
  const series = stats.value?.series || []
  const option = {
    tooltip: { trigger: 'axis' },
    legend: { data: ['摄入', '消耗', '净摄入'] },
    xAxis: {
      type: 'category',
      data: series.map(s => s.date.slice(5)),
      axisLabel: { formatter: (val: string) => val.replace('-', '/') }
    },
    yAxis: { type: 'value', name: '千卡' },
    series: [
      {
        name: '摄入',
        type: 'bar',
        data: series.map(s => s.intake || 0),
        itemStyle: { color: '#409eff', borderRadius: [4, 4, 0, 0] }
      },
      {
        name: '消耗',
        type: 'bar',
        data: series.map(s => s.burn || 0),
        itemStyle: { color: '#f56c6c', borderRadius: [4, 4, 0, 0] }
      },
      {
        name: '净摄入',
        type: 'line',
        smooth: true,
        data: series.map(s => s.net || 0),
        itemStyle: { color: '#67c23a' }
      }
    ],
    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true }
  }
  chart.setOption(option, true)
}

const initWeekChart = () => {
  if (!weekChartRef.value) return
  const chart = echarts.init(weekChartRef.value)

  // 服务端 7 天序列：热量缺口 = 目标 − 摄入 + 消耗
  const target = stats.value?.targetCalories || userStore.targetCalories
  const series = stats.value?.series || []
  const weekData = series.map(s => ({
    date: s.date,
    deficit: Math.round(target - (s.intake || 0) + (s.burn || 0))
  }))

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
  chart.setOption(option, true)
}
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

        <el-row :gutter="20" class="stats-row">
          <el-col :span="8">
            <el-card shadow="hover" class="stat-card">
              <div class="stat-header">
                <span class="stat-title">近 7 天依从率</span>
                <el-icon class="stat-icon" color="#67c23a"><CircleCheck /></el-icon>
              </div>
              <div class="stat-value" :style="{ color: adherenceColor }">{{ stats?.adherenceRate ?? 0 }}<span class="unit">%</span></div>
              <div class="stat-desc">已完成 {{ stats?.doneItems ?? 0 }} / 应做 {{ stats?.plannedItems ?? 0 }} 项（服务端口径）</div>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card shadow="hover" class="stat-card">
              <div class="stat-header">
                <span class="stat-title">训练量</span>
                <el-icon class="stat-icon" color="#e6a23c"><Timer /></el-icon>
              </div>
              <div class="stat-value">{{ stats?.totalMinutes ?? 0 }}<span class="unit">分钟</span></div>
              <div class="stat-desc">
                日均 {{ stats?.avgWeeklyMinutes ?? 0 }} 分钟 · 连续达标 {{ stats?.streakDays ?? 0 }} 天
              </div>
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card shadow="hover" class="stat-card">
              <div class="stat-header">
                <span class="stat-title">RPE 偏差</span>
                <el-icon class="stat-icon" color="#909399"><DataAnalysis /></el-icon>
              </div>
              <div class="stat-value">{{ rpeDeviationText }}</div>
              <div class="stat-desc">
                实际 {{ stats?.avgActualRpe ?? '—' }} / 计划 {{ stats?.avgPlannedRpe ?? '—' }}
              </div>
            </el-card>
          </el-col>
        </el-row>

        <el-card shadow="hover" class="chart-card" v-loading="loading">
          <template #header>
            <div class="card-header">
              <span>今日训练卡</span>
              <el-tag v-if="todayPlan?.restDay" type="info" size="small">今天休息</el-tag>
              <el-tag v-else-if="todayPlan?.planId" type="success" size="small">
                v{{ todayPlan?.version }} · {{ todayPlan?.dayLabel }} · {{ todayPlan?.focus }}
              </el-tag>
            </div>
          </template>
          <div v-if="!todayPlan?.planId" class="empty-workout">
            <el-icon size="40" color="#c0c4cc"><Calendar /></el-icon>
            <p>还没有生效中的训练计划</p>
            <el-button type="primary" size="small" @click="$router.push('/plan')">去生成计划</el-button>
          </div>
          <div v-else class="today-plan">
            <div class="hr-line">
              <span class="hr-label">目标心率区间</span>
              <span class="hr-value">{{ todayPlan.hrLow }}–{{ todayPlan.hrHigh }} bpm</span>
              <span class="hr-note">{{ todayPlan.hrNote }}</span>
            </div>
            <div v-if="todayPlan.items.length === 0" class="empty-workout">
              <p>{{ todayPlan.dayLabel }}为休息日，建议轻度活动与拉伸</p>
            </div>
            <div v-else class="today-items">
              <div v-for="item in todayPlan.items" :key="item.planDetailId" class="today-item"
                   :class="{ done: item.done, risk: item.warningLevel === 'DANGER' }">
                <div class="today-item-main">
                  <span class="today-item-name">{{ item.exerciseName }}</span>
                  <span class="today-item-meta">{{ item.sets }} 组 × {{ item.reps }} · RPE {{ item.rpe }}</span>
                </div>
                <el-tag v-if="item.warningText" size="small" :type="item.warningLevel === 'DANGER' ? 'danger' : 'warning'">
                  {{ item.warningText }}
                </el-tag>
                <el-tag v-if="item.done" size="small" type="success">已完成</el-tag>
                <el-button v-else size="small" type="primary" link @click="$router.push('/exercise')">去打卡</el-button>
              </div>
            </div>
          </div>
        </el-card>

        <el-card shadow="hover" class="chart-card">
          <template #header>
            <div class="card-header">
              <span>热量收支（近 7 天）</span>
              <el-tag type="info" size="small">净摄入 {{ stats?.netCalories ?? 0 }} 千卡</el-tag>
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
        <el-card shadow="hover" class="advice-card">
          <template #header>
            <div class="card-header">
              <span>调整建议</span>
              <el-tag :type="adviceTagType" size="small">{{ advice?.decision ?? '—' }}</el-tag>
            </div>
          </template>
          <div v-if="!advice" class="advice-empty">样本不足，先完成 2 次训练再来看看</div>
          <template v-else>
            <div class="advice-title">{{ advice.title }}</div>
            <ul class="advice-list">
              <li v-for="(r, i) in advice.reasons" :key="'r' + i">{{ r }}</li>
            </ul>
            <ul class="advice-list suggestion">
              <li v-for="(s, i) in advice.suggestions" :key="'s' + i">{{ s }}</li>
            </ul>
            <el-button v-if="advice.canAutoApply" type="primary" size="small" :loading="advancing"
                       @click="applyAdvice">
              按建议生成下一版
            </el-button>
          </template>
        </el-card>

        <el-card shadow="hover" class="risk-card">
          <template #header>
            <div class="card-header">
              <span>风险提示</span>
              <el-tag v-if="(stats?.risks?.length ?? 0) === 0" type="success" size="small">无异常</el-tag>
            </div>
          </template>
          <div v-if="(stats?.risks?.length ?? 0) === 0" class="advice-empty">
            依从率与主观强度都在合理区间
          </div>
          <div v-for="(risk, i) in stats?.risks" :key="risk.code + i" class="risk-item"
               :class="'risk-' + risk.level.toLowerCase()">
            <div class="risk-head">
              <el-tag size="small" :type="riskTagType(risk.level)">{{ riskLevelLabel(risk.level) }}</el-tag>
              <span class="risk-code">{{ risk.code }}</span>
            </div>
            <div class="risk-msg">{{ risk.message }}</div>
            <div class="risk-sug">{{ risk.suggestion }}</div>
          </div>
        </el-card>

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

/* P4 执行闭环卡片 */
.advice-card,
.risk-card {
  border-radius: 12px;
  margin-bottom: 20px;
}

.advice-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}

.advice-list {
  margin: 0 0 8px;
  padding-left: 18px;
  font-size: 13px;
  color: #606266;
  line-height: 1.7;
}

.advice-list.suggestion {
  color: #409eff;
}

.advice-empty {
  font-size: 13px;
  color: #c0c4cc;
  padding: 8px 0;
}

.risk-item {
  padding: 10px;
  border-radius: 8px;
  margin-bottom: 8px;
  background: #f5f7fa;
  border-left: 3px solid #909399;
}

.risk-item.risk-danger {
  background: #fef0f0;
  border-left-color: #f56c6c;
}

.risk-item.risk-warn {
  background: #fdf6ec;
  border-left-color: #e6a23c;
}

.risk-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.risk-code {
  font-size: 12px;
  color: #909399;
}

.risk-msg {
  font-size: 13px;
  color: #303133;
}

.risk-sug {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.today-plan {
  font-size: 14px;
}

.hr-line {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 8px 12px;
  background: #f0f9eb;
  border-radius: 8px;
  margin-bottom: 12px;
}

.hr-label {
  color: #909399;
  font-size: 13px;
}

.hr-value {
  font-weight: 600;
  color: #529b2e;
}

.hr-note {
  color: #909399;
  font-size: 12px;
}

.today-items {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.today-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: #f5f7fa;
  border-radius: 8px;
}

.today-item.done {
  background: #f0f9eb;
}

.today-item.risk {
  background: #fef0f0;
}

.today-item-main {
  flex: 1;
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}

.today-item-name {
  font-weight: 500;
}

.today-item-meta {
  font-size: 12px;
  color: #909399;
}
</style>