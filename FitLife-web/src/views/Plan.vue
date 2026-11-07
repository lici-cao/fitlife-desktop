<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { planApi, exerciseLibApi } from '../api'

/** 生成参数 */
const form = ref({
  goal: '减脂',
  experience: '初级',
  weeklyDays: 3,
  sessionMinutes: 45,
  equipments: ['徒手'],
  injuries: [] as string[]
})

const goalOptions = ['减脂', '增肌', '塑形', '体能']
const experienceOptions = ['初级', '中级', '高级']
const equipmentOptions = ['徒手', '哑铃', '杠铃', '器械', '弹力带', '壶铃']
const injuryOptions = ['膝关节', '腰椎', '肩关节', '腕关节']

const loading = ref(false)
const plan = ref<any>(null)
const history = ref<any[]>([])
const activeDay = ref(1)
const libStats = ref<any>(null)
const showConfig = ref(true)

const dayTabs = computed(() => {
  if (!plan.value?.days) return []
  return plan.value.days.map((d: any) => ({
    dayOfWeek: d.dayOfWeek,
    label: d.dayLabel,
    focus: d.focus,
    restDay: d.restDay
  }))
})

const currentDay = computed(() => {
  if (!plan.value?.days) return null
  return plan.value.days.find((d: any) => d.dayOfWeek === activeDay.value) || plan.value.days[0]
})

onMounted(async () => {
  await Promise.all([loadPlan(), loadHistory(), loadLibStats()])
})

async function loadPlan() {
  try {
    const res: any = await planApi.current()
    if (res.data?.code === 200 && res.data.data) {
      plan.value = res.data.data
      pickFirstTrainDay()
      showConfig.value = false
    }
  } catch (e) {
    console.warn('加载计划失败', e)
  }
}

async function loadHistory() {
  try {
    const res: any = await planApi.list()
    if (res.data?.code === 200) {
      history.value = res.data.data || []
    }
  } catch (e) {
    console.warn('加载历史失败', e)
  }
}

async function loadLibStats() {
  try {
    const res: any = await exerciseLibApi.stats()
    if (res.data?.code === 200) {
      libStats.value = res.data.data
    }
  } catch (e) {
    console.warn('加载动作库统计失败', e)
  }
}

function pickFirstTrainDay() {
  const first = plan.value?.days?.find((d: any) => !d.restDay)
  if (first) activeDay.value = first.dayOfWeek
}

async function generate() {
  if (form.value.equipments.length === 0) {
    ElMessage.warning('请至少选择一种可用器械')
    return
  }
  loading.value = true
  try {
    const res: any = await planApi.generate({
      goal: form.value.goal,
      experience: form.value.experience,
      weeklyDays: form.value.weeklyDays,
      sessionMinutes: form.value.sessionMinutes,
      equipments: form.value.equipments.join(','),
      injuries: form.value.injuries.join(','),
      force: true
    })
    if (res.data?.code === 200) {
      plan.value = res.data.data
      pickFirstTrainDay()
      showConfig.value = false
      ElMessage.success(plan.value?.aiGenerated ? 'AI 计划生成成功' : '已生成规则模板计划')
      await loadHistory()
    } else {
      ElMessage.error(res.data?.message || '生成失败')
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '生成失败，请检查后端 AI 配置')
  } finally {
    loading.value = false
  }
}

async function viewHistory(id: number) {
  try {
    const res: any = await planApi.detail(id)
    if (res.data?.code === 200) {
      plan.value = res.data.data
      pickFirstTrainDay()
      showConfig.value = false
    }
  } catch (e) {
    ElMessage.error('加载计划失败')
  }
}

async function removeHistory(id: number) {
  try {
    await ElMessageBox.confirm('确定删除该计划？', '提示', { type: 'warning' })
    const res: any = await planApi.remove(id)
    if (res.data?.code === 200) {
      ElMessage.success('已删除')
      await loadHistory()
    }
  } catch (e) {
    /* 取消 */
  }
}

</script>

<template>
  <div class="plan-page">
    <!-- 顶部概览 -->
    <el-row :gutter="16" class="mb16">
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">动作库规模</div>
          <div class="stat-value">{{ libStats?.total || 0 }}</div>
          <div class="stat-sub">个已审核动作</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">本周训练</div>
          <div class="stat-value">{{ plan?.weeklyDays || 0 }}</div>
          <div class="stat-sub">天 / 周</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">有氧总时长</div>
          <div class="stat-value">{{ plan?.weeklyCardioMin || 0 }}</div>
          <div class="stat-sub">分钟 / 周</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">预估消耗</div>
          <div class="stat-value">{{ plan?.weeklyCalories || 0 }}</div>
          <div class="stat-sub">kcal / 周</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <!-- 左侧：生成配置 + 历史 -->
      <el-col :span="7">
        <el-card shadow="never" class="mb16">
          <template #header>
            <div class="card-header">
              <span>生成训练计划</span>
              <el-button link type="primary" @click="showConfig = !showConfig">
                {{ showConfig ? '收起' : '展开' }}
              </el-button>
            </div>
          </template>

          <div v-show="showConfig">
            <el-form label-position="top" size="default">
              <el-form-item label="健身目标">
                <el-radio-group v-model="form.goal">
                  <el-radio-button v-for="g in goalOptions" :key="g" :value="g">{{ g }}</el-radio-button>
                </el-radio-group>
              </el-form-item>

              <el-form-item label="运动经验">
                <el-select v-model="form.experience" style="width: 100%">
                  <el-option v-for="e in experienceOptions" :key="e" :label="e" :value="e" />
                </el-select>
              </el-form-item>

              <el-form-item label="每周训练天数">
                <el-slider v-model="form.weeklyDays" :min="1" :max="7" show-stops />
              </el-form-item>

              <el-form-item label="单次训练时长（分钟）">
                <el-slider v-model="form.sessionMinutes" :min="15" :max="120" :step="15" show-stops />
              </el-form-item>

              <el-form-item label="可用器械">
                <el-checkbox-group v-model="form.equipments">
                  <el-checkbox v-for="eq in equipmentOptions" :key="eq" :value="eq" :label="eq" />
                </el-checkbox-group>
              </el-form-item>

              <el-form-item label="伤病史 / 禁忌部位（将自动排除相关动作）">
                <el-checkbox-group v-model="form.injuries">
                  <el-checkbox v-for="inj in injuryOptions" :key="inj" :value="inj" :label="inj" />
                </el-checkbox-group>
              </el-form-item>

              <el-button
                type="primary"
                :loading="loading"
                style="width: 100%"
                @click="generate"
              >
                {{ loading ? 'AI 正在编排…' : '生成我的训练计划' }}
              </el-button>
            </el-form>
          </div>
        </el-card>

        <el-card shadow="never">
          <template #header><span>历史计划</span></template>
          <el-empty v-if="history.length === 0" description="暂无历史计划" :image-size="60" />
          <div v-for="h in history" :key="h.id" class="history-item">
            <div class="history-main">
              <div class="history-title">{{ h.title }}</div>
              <div class="history-meta">
                {{ h.goal }} · {{ h.weeklyDays }}天/周
                <el-tag v-if="h.status === 'ACTIVE'" size="small" type="success">当前</el-tag>
              </div>
            </div>
            <div class="history-ops">
              <el-button link type="primary" size="small" @click="viewHistory(h.id)">查看</el-button>
              <el-button link type="danger" size="small" @click="removeHistory(h.id)">删除</el-button>
            </div>
          </div>
        </el-card>
      </el-col>

      <!-- 右侧：计划内容 -->
      <el-col :span="17">
        <el-card v-if="!plan" shadow="never">
          <el-empty description="还没有训练计划，请在左侧配置后点击生成" />
        </el-card>

        <template v-else>
          <el-card shadow="never" class="mb16">
            <template #header>
              <div class="card-header">
                <span>{{ plan.title }}</span>
                <el-tag :type="plan.aiGenerated ? 'success' : 'warning'" size="small">
                  {{ plan.aiGenerated ? 'AI 生成' : '规则模板' }}
                </el-tag>
              </div>
            </template>
            <div class="plan-summary">{{ plan.summary }}</div>
            <el-alert
              v-if="plan.note"
              :title="plan.note"
              type="info"
              :closable="false"
              show-icon
              class="mt8"
            />
          </el-card>

          <el-card shadow="never">
            <el-tabs v-model="activeDay">
              <el-tab-pane
                v-for="d in dayTabs"
                :key="d.dayOfWeek"
                :name="d.dayOfWeek"
                :label="d.label"
              >
                <template #label>
                  <span class="tab-label">
                    {{ d.label }}
                    <el-badge v-if="!d.restDay" is-dot type="success" />
                  </span>
                </template>
              </el-tab-pane>
            </el-tabs>

            <div v-if="currentDay?.restDay" class="rest-day">
              <el-icon :size="40"><Sunny /></el-icon>
              <div class="rest-title">休息日</div>
              <div class="rest-tip">充分休息有助于肌肉恢复与生长，可安排轻度拉伸或散步。</div>
            </div>

            <div v-else>
              <div class="day-header">
                <div class="day-focus">{{ currentDay?.focus }}</div>
                <div class="day-meta">
                  约 {{ currentDay?.estMinutes }} 分钟 · 预估 {{ currentDay?.estCalories }} kcal
                </div>
              </div>

              <el-table :data="currentDay?.items || []" style="width: 100%" stripe>
                <el-table-column prop="seq" label="#" width="50" align="center" />
                <el-table-column prop="exerciseName" label="动作" min-width="140">
                  <template #default="{ row }">
                    <div class="ex-name">{{ row.exerciseName }}</div>
                    <div class="ex-desc">{{ row.description }}</div>
                  </template>
                </el-table-column>
                <el-table-column prop="muscleGroup" label="肌群" width="80" align="center" />
                <el-table-column prop="sets" label="组数" width="70" align="center" />
                <el-table-column prop="reps" label="次数/时长" width="100" align="center" />
                <el-table-column label="休息" width="80" align="center">
                  <template #default="{ row }">{{ row.restSec }}s</template>
                </el-table-column>
                <el-table-column label="强度" width="80" align="center">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.rpe >= 8 ? 'danger' : row.rpe >= 6 ? 'warning' : 'success'">
                      RPE {{ row.rpe }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="消耗" width="90" align="center">
                  <template #default="{ row }">
                    <span class="cal">{{ row.estCalories }} kcal</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-card>
        </template>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.plan-page { padding: 0; }
.mb16 { margin-bottom: 16px; }
.mt8 { margin-top: 8px; }

.stat-card { border-radius: 12px; text-align: center; }
.stat-label { font-size: 13px; color: #909399; margin-bottom: 6px; }
.stat-value { font-size: 28px; font-weight: 700; color: #409eff; line-height: 1.2; }
.stat-sub { font-size: 12px; color: #c0c4cc; margin-top: 4px; }

.card-header { display: flex; align-items: center; justify-content: space-between; }
.plan-summary { font-size: 14px; line-height: 1.8; color: #606266; }

.history-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 0; border-bottom: 1px solid #f0f0f0;
}
.history-item:last-child { border-bottom: none; }
.history-title { font-size: 14px; font-weight: 500; color: #303133; }
.history-meta { font-size: 12px; color: #909399; margin-top: 4px; display: flex; gap: 6px; align-items: center; }
.history-ops { display: flex; gap: 2px; }

.tab-label { display: inline-flex; align-items: center; gap: 4px; }

.day-header {
  display: flex; align-items: baseline; justify-content: space-between;
  padding: 8px 0 14px;
}
.day-focus { font-size: 16px; font-weight: 600; color: #303133; }
.day-meta { font-size: 13px; color: #909399; }

.ex-name { font-size: 14px; font-weight: 500; color: #303133; }
.ex-desc { font-size: 12px; color: #909399; margin-top: 2px; line-height: 1.5; }
.cal { color: #f56c6c; font-weight: 600; font-size: 13px; }

.rest-day { text-align: center; padding: 40px 0; color: #909399; }
.rest-title { font-size: 16px; font-weight: 600; margin: 10px 0 6px; color: #606266; }
.rest-tip { font-size: 13px; color: #a8abb2; }
</style>
