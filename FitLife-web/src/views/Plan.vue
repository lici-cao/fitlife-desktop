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
const validationOpen = ref<string[]>([])

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

/** P2：把历史版本设为生效（回滚） */
async function activateHistory(id: number) {
  try {
    await ElMessageBox.confirm('把该版本设为当前生效计划？当前版本会归档，不会删除。', '版本回滚', { type: 'info' })
    const res: any = await planApi.activate(id)
    if (res.data?.code === 200) {
      ElMessage.success(res.data.message || '已回滚')
      await Promise.all([loadPlan(), loadHistory()])
    } else {
      ElMessage.error(res.data?.message || '回滚失败')
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || '回滚失败')
    }
  }
}

/** P2：来源徽标 */
function sourceTag(link?: string): { label: string; type: string } {
  switch (link) {
    case 'LLM': return { label: '大模型编排', type: 'success' }
    case 'LLM_REPAIRED': return { label: '大模型·已修复', type: 'primary' }
    case 'TEMPLATE': return { label: '规则模板', type: 'warning' }
    default: return { label: '', type: 'info' }
  }
}

const sourceInfo = computed(() => sourceTag(plan.value?.sourceLink))
const validation = computed(() => plan.value?.validationReport || null)

// ===== P3：对话式局部修订 =====
const reviseInput = ref('')
const reviseLoading = ref(false)
const lastRevise = ref<any>(null)
/** 服务端会话键：同一会话内多轮修订共享上下文 */
const reviseSessionKey = localStorage.getItem('fitlife_revise_session') || ''
const quickPrompts = [
  '把周三的深蹲换成臀桥',
  '深蹲强度太高了，降一点',
  '去掉周三的平板支撑',
  '重新生成一份计划'
]

async function sendRevise(msg?: string) {
  const message = (msg ?? (reviseInput.value || '')).trim()
  if (!message) {
    ElMessage.warning('请输入想怎么调整，例如「把周三的深蹲换成臀桥」')
    return
  }
  reviseLoading.value = true
  try {
    const res: any = await planApi.revise({
      message,
      sessionKey: reviseSessionKey || undefined
    })
    if (res.data?.code === 200) {
      const data = res.data.data || {}
      lastRevise.value = data
      if (data.sessionKey) {
        localStorage.setItem('fitlife_revise_session', data.sessionKey)
      }
      if (data.action === 'REVISE' && data.plan) {
        plan.value = data.plan
        pickFirstTrainDay()
        ElMessage.success(`已生成 v${data.plan.version}（对话修订）`)
        await loadHistory()
      } else if (data.action === 'GENERATE' && data.plan) {
        plan.value = data.plan
        pickFirstTrainDay()
        ElMessage.success('已重新生成计划')
        await loadHistory()
      } else {
        ElMessage.info(data.reply || data.note || '已收到指令')
      }
    } else {
      ElMessage.error(res.data?.message || '修订失败')
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '修订失败，请稍后重试')
  } finally {
    reviseLoading.value = false
  }
}

function diffTagType(t?: string): string {
  switch (t) {
    case 'ADDED': return 'success'
    case 'REMOVED': return 'danger'
    default: return 'warning'
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
              <div class="history-title">
                {{ h.title }}
                <el-tag v-if="h.version" size="small" effect="plain" class="ver-tag">v{{ h.version }}</el-tag>
              </div>
              <div class="history-meta">
                {{ h.goal }} · {{ h.weeklyDays }}天/周
                <el-tag v-if="h.status === 'ACTIVE'" size="small" type="success">当前</el-tag>
                <el-tag v-else-if="sourceTag(h.sourceLink).label" size="small" :type="sourceTag(h.sourceLink).type as any" effect="plain">
                  {{ sourceTag(h.sourceLink).label }}
                </el-tag>
              </div>
            </div>
            <div class="history-ops">
              <el-button link type="primary" size="small" @click="viewHistory(h.id)">查看</el-button>
              <el-button v-if="h.status !== 'ACTIVE'" link type="warning" size="small" @click="activateHistory(h.id)">回滚</el-button>
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
                <span>
                  {{ plan.title }}
                  <el-tag v-if="plan.version" size="small" effect="plain" class="ver-tag">v{{ plan.version }}</el-tag>
                </span>
                <span class="header-tags">
                  <el-tag v-if="sourceInfo.label" size="small" :type="sourceInfo.type as any">{{ sourceInfo.label }}</el-tag>
                  <el-tag :type="plan.aiGenerated ? 'success' : 'warning'" size="small">
                    {{ plan.aiGenerated ? 'AI 生成' : '规则模板' }}
                  </el-tag>
                </span>
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

            <!-- P2：规则校验报告 -->
            <el-collapse v-if="validation" v-model="validationOpen" class="mt8">
              <el-collapse-item name="v">
                <template #title>
                  <span class="valid-title">
                    科学性校验报告
                    <el-tag size="small" :type="validation.passed ? 'success' : 'danger'" class="ver-tag">
                      {{ validation.passed ? '通过' : '存在风险' }}
                    </el-tag>
                    <el-tag size="small" effect="plain" class="ver-tag">得分 {{ validation.score }}</el-tag>
                  </span>
                </template>
                <el-table :data="validation.checks || []" size="small" style="width: 100%">
                  <el-table-column prop="code" label="项" width="60" align="center" />
                  <el-table-column prop="name" label="校验项" width="140" />
                  <el-table-column label="级别" width="80" align="center">
                    <template #default="{ row }">
                      <el-tag size="small" :type="row.level === 'ERROR' ? 'danger' : row.level === 'WARN' ? 'warning' : 'success'">
                        {{ row.level }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="detail" label="说明" min-width="220" />
                </el-table>
                <ul v-if="validation.riskNotes?.length" class="risk-notes">
                  <li v-for="(n, i) in validation.riskNotes" :key="i">{{ n }}</li>
                </ul>
              </el-collapse-item>
            </el-collapse>

            <!-- P3：对话式微调 -->
            <div class="revise-box mt8">
              <div class="revise-title">
                对话式微调
                <span class="revise-sub">用一句话改计划，改动会生成新版本，可随时回滚</span>
              </div>
              <div class="revise-input">
                <el-input
                  v-model="reviseInput"
                  placeholder="例如：把周三的深蹲换成臀桥 / 深蹲强度降一点 / 去掉平板支撑"
                  :disabled="reviseLoading"
                  @keyup.enter="sendRevise()"
                />
                <el-button type="primary" :loading="reviseLoading" @click="sendRevise()">
                  {{ reviseLoading ? '处理中…' : '发送' }}
                </el-button>
              </div>
              <div class="revise-quick">
                <el-tag
                  v-for="q in quickPrompts"
                  :key="q"
                  class="revise-chip"
                  effect="plain"
                  @click="sendRevise(q)"
                >{{ q }}</el-tag>
              </div>

              <!-- 上一轮修订结果 -->
              <div v-if="lastRevise" class="revise-result">
                <div class="revise-reply">{{ lastRevise.reply || lastRevise.note }}</div>
                <div v-if="lastRevise.intentByLlm !== undefined" class="revise-meta">
                  意图识别：{{ lastRevise.intentByLlm ? '大模型' : '规则' }}
                  <el-tag v-if="lastRevise.action" size="small" effect="plain" class="ver-tag">{{ lastRevise.action }}</el-tag>
                </div>
                <div v-if="lastRevise.diff?.length" class="revise-diff">
                  <div v-for="(d, i) in lastRevise.diff" :key="i" class="diff-item">
                    <el-tag size="small" :type="diffTagType(d.type) as any">{{ d.type }}</el-tag>
                    <span class="diff-day">{{ d.dayLabel }}</span>
                    <span class="diff-name">{{ d.exerciseName }}</span>
                    <span class="diff-detail">{{ d.detail }}</span>
                  </div>
                </div>
              </div>
            </div>
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

.ver-tag { margin-left: 6px; }
.header-tags { display: inline-flex; gap: 6px; align-items: center; }
.valid-title { display: inline-flex; align-items: center; font-size: 13px; color: #606266; }
.risk-notes { margin: 8px 0 0; padding-left: 18px; font-size: 12px; color: #909399; line-height: 1.8; }

.revise-box { border-top: 1px dashed #e4e7ed; padding-top: 10px; }
.revise-title { font-size: 14px; font-weight: 600; color: #303133; display: flex; align-items: baseline; gap: 8px; }
.revise-sub { font-size: 12px; font-weight: 400; color: #909399; }
.revise-input { display: flex; gap: 8px; margin-top: 8px; }
.revise-quick { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.revise-chip { cursor: pointer; }
.revise-result { margin-top: 10px; background: #f5f7fa; border-radius: 8px; padding: 10px 12px; }
.revise-reply { font-size: 13px; color: #303133; line-height: 1.7; }
.revise-meta { font-size: 12px; color: #909399; margin-top: 6px; }
.revise-diff { margin-top: 8px; display: flex; flex-direction: column; gap: 6px; }
.diff-item { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.diff-day { color: #606266; min-width: 40px; }
.diff-name { color: #303133; font-weight: 500; }
.diff-detail { color: #909399; flex: 1; }

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
