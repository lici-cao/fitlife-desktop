<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api'

interface Submission {
  id: number
  username: string
  name: string
  category: string
  muscleGroup: string
  equipment: string
  difficulty: number
  metValue: number
  description: string
  contraindication: string
  sourceType: string
  sourceTitle: string
  sourceUrl: string
  sourcePage: string
  evidenceLevel: string
  licenseNote: string
  status: string
  reviewNote: string
  createdAt: string
}

const loading = ref(false)
const rows = ref<Submission[]>([])
const total = ref(0)
const pending = ref(0)
const status = ref('PENDING')
const rubric = ref<string[]>([])
const stats = ref<any>(null)

const statusOptions = [
  { value: 'PENDING', label: '待审核' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: '', label: '全部' }
]

onMounted(load)

async function load() {
  loading.value = true
  try {
    const res: any = await adminApi.exerciseSubmissions({ status: status.value, page: 0, size: 50 })
    if (res.data?.code === 200) {
      rows.value = res.data.data?.rows || []
      total.value = res.data.data?.total || 0
      pending.value = res.data.data?.pending || 0
      rubric.value = res.data.data?.reviewRubric || []
    } else {
      ElMessage.error(res.data?.message || '加载失败')
    }
    const st: any = await adminApi.contentStats()
    if (st.data?.code === 200) stats.value = st.data.data
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '需要管理员权限')
  } finally {
    loading.value = false
  }
}

async function approve(row: Submission) {
  try {
    const { value } = await ElMessageBox.prompt(
      `确认来源可查、表述与来源一致、MET/难度量级相符后再通过。\n动作：${row.name}`,
      '通过并入库',
      { inputPlaceholder: '审核意见（可留空）', confirmButtonText: '通过', cancelButtonText: '取消' }
    )
    const res: any = await adminApi.approveSubmission(row.id, value || undefined)
    if (res.data?.code === 200) {
      ElMessage.success('已通过并加入动作库')
      await load()
    } else {
      ElMessage.error(res.data?.message || '操作失败')
    }
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

async function reject(row: Submission) {
  try {
    const { value } = await ElMessageBox.prompt(
      '驳回必须写清原因，投稿人会看到这段说明。',
      '驳回投稿',
      { inputPlaceholder: '如：来源链接打不开 / 描述与来源不符', confirmButtonText: '驳回', cancelButtonText: '取消' }
    )
    if (!value || !value.trim()) {
      ElMessage.warning('驳回必须填写原因')
      return
    }
    const res: any = await adminApi.rejectSubmission(row.id, value.trim())
    if (res.data?.code === 200) {
      ElMessage.success('已驳回')
      await load()
    } else {
      ElMessage.error(res.data?.message || '操作失败')
    }
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

function statusType(s: string) {
  return s === 'APPROVED' ? 'success' : s === 'REJECTED' ? 'danger' : 'warning'
}
function difficultyText(d: number) {
  return ['', '初级', '中级', '高级'][d] || '初级'
}
</script>

<template>
  <div class="review-page">
    <div class="page-head">
      <div>
        <h2>内容审核</h2>
        <p>用户投稿的动作必须能在来源里查到才会入库；审核核的是可溯源性，不是替第三方背书。</p>
      </div>
      <div class="head-ops">
        <el-tag v-if="pending" type="warning" effect="plain">待审 {{ pending }}</el-tag>
        <el-button @click="load" :loading="loading">刷新</el-button>
      </div>
    </div>

    <el-row :gutter="14" class="mb14" v-if="stats">
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">动作寻源覆盖</div>
          <div class="stat-value">{{ stats.exercises?.coverage }}%</div>
          <div class="stat-sub">{{ stats.exercises?.withSource }} / {{ stats.exercises?.total }} 条带链接</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">证据等级 A</div>
          <div class="stat-value">{{ stats.exercises?.evidenceA }}</div>
          <div class="stat-sub">B {{ stats.exercises?.evidenceB }} · C {{ stats.exercises?.evidenceC }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">知识条目 / 食物</div>
          <div class="stat-value">{{ stats.knowledgeItems }} / {{ stats.foodItems }}</div>
          <div class="stat-sub">知识库与成分表条目数</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-label">投稿流水</div>
          <div class="stat-value">{{ stats.submissions?.approved }}</div>
          <div class="stat-sub">待审 {{ stats.submissions?.pending }} · 驳回 {{ stats.submissions?.rejected }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card v-if="rubric.length" shadow="never" class="mb14">
      <div class="rubric-title">审核口径（四查）</div>
      <ul class="rubric">
        <li v-for="(r, i) in rubric" :key="i">{{ r }}</li>
      </ul>
    </el-card>

    <el-card shadow="never">
      <div class="filter-bar">
        <el-select v-model="status" style="width: 150px" @change="load">
          <el-option v-for="o in statusOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <span class="count">共 {{ total }} 条</span>
      </div>

      <el-table :data="rows" v-loading="loading" size="small" stripe max-height="540">
        <el-table-column label="动作" min-width="150">
          <template #default="{ row }">
            <div class="ex-name">{{ row.name }}</div>
            <div class="ex-meta">{{ row.category }} · {{ row.muscleGroup }} · {{ row.equipment }} · {{ difficultyText(row.difficulty) }} · MET {{ row.metValue }}</div>
          </template>
        </el-table-column>
        <el-table-column label="来源" min-width="200">
          <template #default="{ row }">
            <a v-if="row.sourceUrl" :href="row.sourceUrl" target="_blank" rel="noopener" class="src-link">
              {{ row.sourceTitle || row.sourceUrl }}
            </a>
            <div class="ex-meta">
              证据 {{ row.evidenceLevel || '—' }}<span v-if="row.sourcePage"> · {{ row.sourcePage }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
        <el-table-column prop="contraindication" label="禁忌" width="100" />
        <el-table-column prop="username" label="投稿人" width="100" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reviewNote" label="意见" min-width="140" show-overflow-tooltip />
        <el-table-column label="操作" width="130" align="center" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button size="small" type="success" link @click="approve(row)">通过</el-button>
              <el-button size="small" type="danger" link @click="reject(row)">驳回</el-button>
            </template>
            <span v-else class="muted">已处理</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && rows.length === 0" description="没有符合条件的投稿" />
    </el-card>
  </div>
</template>

<style scoped>
.review-page { padding: 0; }
.mb14 { margin-bottom: 14px; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 14px; }
.page-head h2 { margin: 0 0 6px; font-size: 20px; color: #303133; }
.page-head p { margin: 0; font-size: 13px; color: #909399; }
.head-ops { display: flex; gap: 8px; align-items: center; }
.stat-card { border-radius: 12px; text-align: center; }
.stat-label { font-size: 13px; color: #909399; margin-bottom: 6px; }
.stat-value { font-size: 26px; font-weight: 700; color: #409eff; line-height: 1.2; }
.stat-sub { font-size: 12px; color: #c0c4cc; margin-top: 4px; }
.rubric-title { font-size: 14px; font-weight: 600; color: #303133; margin-bottom: 6px; }
.rubric { margin: 0; padding-left: 18px; font-size: 13px; color: #606266; line-height: 1.9; }
.filter-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.count { font-size: 13px; color: #909399; margin-left: auto; }
.ex-name { font-size: 14px; font-weight: 600; color: #303133; }
.ex-meta { font-size: 12px; color: #909399; margin-top: 2px; }
.src-link { color: #409eff; font-size: 12px; text-decoration: none; }
.src-link:hover { text-decoration: underline; }
.muted { color: #c0c4cc; font-size: 12px; }
</style>