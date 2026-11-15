<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { exerciseLibApi } from '../api'

const router = useRouter()

interface ExerciseItem {
  id: number
  name: string
  category: string
  muscleGroup: string
  equipment: string
  difficulty: number
  metValue: number
  defaultSets: number
  defaultReps: string
  description: string
  contraindication: string
  // P5 寻源字段
  sourceType?: string
  sourceTitle?: string
  sourceUrl?: string
  sourcePage?: string
  evidenceLevel?: string
  licenseNote?: string
  lastVerifiedAt?: string
}

const loading = ref(false)
const all = ref<ExerciseItem[]>([])
const stats = ref<any>(null)
const keyword = ref('')
const category = ref('')
const muscleGroup = ref('')
const equipment = ref('')
const maxDifficulty = ref<number | null>(null)

const categoryOptions = ['力量', '有氧', '核心', '柔韧', 'HIIT']
const muscleOptions = ['胸', '背', '肩', '手臂', '腿', '臀', '核心', '全身']
const equipmentOptions = ['徒手', '哑铃', '杠铃', '器械', '弹力带', '壶铃']

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return all.value.filter(e =>
    (!kw || e.name.toLowerCase().includes(kw) || (e.description || '').toLowerCase().includes(kw)) &&
    (!category.value || e.category === category.value) &&
    (!muscleGroup.value || e.muscleGroup === muscleGroup.value) &&
    (!equipment.value || e.equipment === equipment.value) &&
    (maxDifficulty.value === null || e.difficulty <= maxDifficulty.value)
  )
})

const sourceStats = ref<any>(null)
const sourcedCount = computed(() => all.value.filter(e => e.sourceUrl).length)
const categoryCount = computed(() => new Set(all.value.map(e => e.category)).size)
const muscleCount = computed(() => new Set(all.value.map(e => e.muscleGroup)).size)

function difficultyText(d: number) {
  return ['', '初级', '中级', '高级'][d] || '初级'
}
function difficultyType(d: number) {
  return ['', 'success', 'warning', 'danger'][d] || 'success'
}

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [listRes, statRes, srcRes]: any[] = await Promise.all([
      exerciseLibApi.list(),
      exerciseLibApi.stats(),
      exerciseLibApi.sourceStats()
    ])
    if (listRes.data?.code === 200) all.value = listRes.data.data || []
    if (statRes.data?.code === 200) stats.value = statRes.data.data
    if (srcRes.data?.code === 200) sourceStats.value = srcRes.data.data
    if (all.value.length === 0) {
      ElMessage.warning('动作库为空：请重启后端（会自动灌入 100 个动作）或执行 fitlife-mysql.sql')
    }
  } catch (e) {
    ElMessage.error('加载动作库失败，请确认后端已启动')
  } finally {
    loading.value = false
  }
}

/* ===== P5：投稿新动作 ===== */
const submitVisible = ref(false)
const submitting = ref(false)
const mySubs = ref<any[]>([])
const subForm = ref({
  name: '', category: '力量', muscleGroup: '腿', equipment: '徒手',
  difficulty: 2, metValue: 5, defaultSets: 3, defaultReps: '12 次',
  description: '', contraindication: '',
  sourceType: 'GUIDELINE', sourceTitle: '', sourceUrl: '', sourcePage: '',
  evidenceLevel: 'A', licenseNote: ''
})

const sourceTypeOptions = [
  { value: 'GUIDELINE', label: '指南 / 共识' },
  { value: 'TEXTBOOK', label: '教材 / 专著' },
  { value: 'JOURNAL', label: '期刊论文' },
  { value: 'DATABASE', label: '数据库条目' },
  { value: 'COMMUNITY', label: '社区整理' }
]

async function loadMySubs() {
  try {
    const res: any = await exerciseLibApi.mySubmissions()
    if (res.data?.code === 200) mySubs.value = res.data.data || []
  } catch (e) { /* 未登录时忽略 */ }
}

async function doSubmit() {
  if (!subForm.value.name || !subForm.value.sourceUrl || !subForm.value.description) {
    ElMessage.warning('动作名称、来源链接、动作说明为必填')
    return
  }
  submitting.value = true
  try {
    const res: any = await exerciseLibApi.submitSource(subForm.value as any)
    if (res.data?.code === 200) {
      const st = res.data.data?.status
      if (st === 'PENDING') {
        ElMessage.success('已提交，等待管理员审核')
        submitVisible.value = false
      } else {
        ElMessage.warning(res.data.data?.reviewNote || '未通过自动校验，请按提示修改')
      }
      await loadMySubs()
    } else {
      ElMessage.error(res.data?.message || '提交失败')
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '提交失败')
  } finally {
    submitting.value = false
  }
}

function statusLabel(s?: string) {
  return s === 'APPROVED' ? '已通过' : s === 'REJECTED' ? '已驳回' : '待审核'
}
function statusType(s?: string) {
  return s === 'APPROVED' ? 'success' : s === 'REJECTED' ? 'danger' : 'warning'
}
function evidenceType(l?: string) {
  return l === 'A' ? 'success' : l === 'B' ? 'primary' : 'info'
}

function resetFilter() {
  keyword.value = ''
  category.value = ''
  muscleGroup.value = ''
  equipment.value = ''
  maxDifficulty.value = null
}
</script>

<template>
  <div class="lib-page">
    <div class="page-head">
      <div>
        <h2>动作库</h2>
        <p>系统内置已审核动作，AI 训练计划与运动搭配只会从这里挑选，不会编造动作。</p>
      </div>
      <div class="head-ops">
        <el-button @click="load" :loading="loading">刷新</el-button>
        <el-button @click="submitVisible = true; loadMySubs()">投稿新动作</el-button>
        <el-button type="primary" @click="router.push('/plan')">生成训练计划</el-button>
      </div>
    </div>

    <el-row :gutter="16" class="mb16">
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">动作总数</div>
          <div class="stat-value">{{ all.length }}</div>
          <div class="stat-sub">个已审核动作</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">训练类别</div>
          <div class="stat-value">{{ categoryCount }}</div>
          <div class="stat-sub">力量 / 有氧 / 核心 / 柔韧 / HIIT</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">覆盖肌群</div>
          <div class="stat-value">{{ muscleCount }}</div>
          <div class="stat-sub">胸背肩臂腿臀核心全身</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">可溯源</div>
          <div class="stat-value">{{ sourcedCount }}/{{ all.length }}</div>
          <div class="stat-sub">带来源链接的动作</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">安全标记</div>
          <div class="stat-value">{{ all.filter(e => e.contraindication).length }}</div>
          <div class="stat-sub">带禁忌症标签的动作</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="mb16">
      <div class="filter-bar">
        <el-input v-model="keyword" placeholder="搜索动作名称 / 动作要领" clearable style="width: 230px" />
        <el-select v-model="category" placeholder="类别" clearable style="width: 130px">
          <el-option v-for="c in categoryOptions" :key="c" :label="c" :value="c" />
        </el-select>
        <el-select v-model="muscleGroup" placeholder="肌群" clearable style="width: 130px">
          <el-option v-for="m in muscleOptions" :key="m" :label="m" :value="m" />
        </el-select>
        <el-select v-model="equipment" placeholder="器械" clearable style="width: 130px">
          <el-option v-for="e in equipmentOptions" :key="e" :label="e" :value="e" />
        </el-select>
        <el-select v-model="maxDifficulty" placeholder="最高难度" clearable style="width: 140px">
          <el-option label="仅初级" :value="1" />
          <el-option label="中级及以下" :value="2" />
          <el-option label="全部难度" :value="3" />
        </el-select>
        <el-button @click="resetFilter">重置</el-button>
        <span class="result-count">共 {{ filtered.length }} 条</span>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table :data="filtered" v-loading="loading" stripe style="width: 100%" max-height="620">
        <el-table-column prop="name" label="动作" min-width="130" fixed>
          <template #default="{ row }">
            <div class="ex-name">{{ row.name }}</div>
            <div class="ex-desc">{{ row.description }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="category" label="类别" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.category }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="muscleGroup" label="肌群" width="80" align="center" />
        <el-table-column prop="equipment" label="器械" width="90" align="center" />
        <el-table-column label="难度" width="85" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="difficultyType(row.difficulty)">{{ difficultyText(row.difficulty) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="组次" width="110" align="center">
          <template #default="{ row }">{{ row.defaultSets }} 组 × {{ row.defaultReps }}</template>
        </el-table-column>
        <el-table-column label="MET" width="75" align="center">
          <template #default="{ row }">{{ row.metValue }}</template>
        </el-table-column>
        <el-table-column label="禁忌部位" width="120">
          <template #default="{ row }">
            <span v-if="row.contraindication" class="warn">{{ row.contraindication }}</span>
            <span v-else class="muted">无</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" min-width="200">
          <template #default="{ row }">
            <div v-if="row.sourceUrl" class="src-cell">
              <a :href="row.sourceUrl" target="_blank" rel="noopener" class="src-link">
                {{ row.sourceTitle || row.sourceType || '来源' }}
              </a>
              <div class="src-meta">
                <el-tag size="small" :type="evidenceType(row.evidenceLevel)" effect="plain">
                  证据 {{ row.evidenceLevel || '—' }}
                </el-tag>
                <span v-if="row.sourcePage" class="src-page">{{ row.sourcePage }}</span>
                <span v-if="row.lastVerifiedAt" class="src-page">核验 {{ row.lastVerifiedAt }}</span>
              </div>
            </div>
            <span v-else class="muted">未标注来源</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && filtered.length === 0" description="没有符合条件的动作，试试放宽筛选" />
      <div v-if="sourceStats?.policy" class="policy-note">{{ sourceStats.policy }}</div>
    </el-card>

    <!-- P5：投稿新动作 -->
    <el-dialog v-model="submitVisible" title="投稿新动作" width="720px" top="6vh">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="投稿不会直接进动作库：先过机器校验（必填、枚举、MET 1–20、难度 1–3、重名、链接格式），再由管理员核对来源。"
        class="mb12"
      />
      <el-form :model="subForm" label-width="96px" size="small">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="动作名称"><el-input v-model="subForm.name" placeholder="如：哑铃酒杯深蹲" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="类别">
              <el-select v-model="subForm.category" style="width: 100%">
                <el-option v-for="c in categoryOptions" :key="c" :label="c" :value="c" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="主要肌群">
              <el-select v-model="subForm.muscleGroup" style="width: 100%">
                <el-option v-for="m in muscleOptions" :key="m" :label="m" :value="m" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="器械">
              <el-select v-model="subForm.equipment" style="width: 100%">
                <el-option v-for="e in equipmentOptions" :key="e" :label="e" :value="e" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="难度">
              <el-select v-model="subForm.difficulty" style="width: 100%">
                <el-option label="初级" :value="1" />
                <el-option label="中级" :value="2" />
                <el-option label="高级" :value="3" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8"><el-form-item label="MET 值"><el-input-number v-model="subForm.metValue" :min="1" :max="20" :step="0.5" style="width: 100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="默认组数"><el-input-number v-model="subForm.defaultSets" :min="1" :max="6" style="width: 100%" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="动作说明"><el-input v-model="subForm.description" type="textarea" :rows="2" placeholder="写清起始姿势、发力顺序与注意点（≥8 字）" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="禁忌部位"><el-input v-model="subForm.contraindication" placeholder="如：膝关节、腰椎（无则留空）" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="来源类型">
              <el-select v-model="subForm.sourceType" style="width: 100%">
                <el-option v-for="o in sourceTypeOptions" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="证据等级">
              <el-select v-model="subForm.evidenceLevel" style="width: 100%">
                <el-option label="A · 指南/系统综述" value="A" />
                <el-option label="B · 教材/单项研究" value="B" />
                <el-option label="C · 社区整理" value="C" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24"><el-form-item label="来源标题"><el-input v-model="subForm.sourceTitle" placeholder="如：ACSM 运动测试与运动处方指南（第 11 版）" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="来源链接"><el-input v-model="subForm.sourceUrl" placeholder="https://…（必须能打开并定位到该动作）" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="页码/章节"><el-input v-model="subForm.sourcePage" placeholder="如：Chapter 7 / p.182" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="授权说明"><el-input v-model="subForm.licenseNote" placeholder="如：公开指南引用" /></el-form-item></el-col>
        </el-row>
      </el-form>

      <div v-if="mySubs.length" class="my-subs">
        <div class="my-subs-title">我的投稿</div>
        <el-table :data="mySubs" size="small" max-height="180">
          <el-table-column prop="name" label="动作" width="140" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reviewNote" label="审核意见" min-width="200" show-overflow-tooltip />
        </el-table>
      </div>

      <template #footer>
        <el-button @click="submitVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doSubmit">提交审核</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.lib-page { padding: 0; }
.mb16 { margin-bottom: 16px; }
.page-head {
  display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px;
}
.page-head h2 { margin: 0 0 6px; font-size: 20px; color: #303133; }
.page-head p { margin: 0; font-size: 13px; color: #909399; }
.head-ops { display: flex; gap: 8px; }
.stat-card { border-radius: 12px; text-align: center; }
.stat-label { font-size: 13px; color: #909399; margin-bottom: 6px; }
.stat-value { font-size: 28px; font-weight: 700; color: #409eff; line-height: 1.2; }
.stat-sub { font-size: 12px; color: #c0c4cc; margin-top: 4px; }
.filter-bar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.result-count { margin-left: auto; font-size: 13px; color: #909399; }
.ex-name { font-size: 14px; font-weight: 600; color: #303133; }
.ex-desc { font-size: 12px; color: #909399; margin-top: 3px; line-height: 1.5; }
.warn { color: #e6a23c; font-size: 12px; }
.muted { color: #c0c4cc; font-size: 12px; }
.mb12 { margin-bottom: 12px; }
.src-cell { display: flex; flex-direction: column; gap: 3px; }
.src-link { color: #409eff; font-size: 12px; text-decoration: none; }
.src-link:hover { text-decoration: underline; }
.src-meta { display: flex; gap: 6px; align-items: center; }
.src-page { font-size: 11px; color: #a8abb2; }
.policy-note {
  margin-top: 10px; font-size: 12px; color: #909399; line-height: 1.7;
  background: #f5f7fa; border-radius: 8px; padding: 8px 10px;
}
.my-subs { margin-top: 12px; border-top: 1px dashed #e4e7ed; padding-top: 10px; }
.my-subs-title { font-size: 13px; font-weight: 600; color: #606266; margin-bottom: 6px; }
</style>
