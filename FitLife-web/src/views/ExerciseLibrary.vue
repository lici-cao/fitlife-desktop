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
    const [listRes, statRes]: any[] = await Promise.all([
      exerciseLibApi.list(),
      exerciseLibApi.stats()
    ])
    if (listRes.data?.code === 200) all.value = listRes.data.data || []
    if (statRes.data?.code === 200) stats.value = statRes.data.data
    if (all.value.length === 0) {
      ElMessage.warning('动作库为空：请重启后端（会自动灌入 100 个动作）或执行 fitlife-mysql.sql')
    }
  } catch (e) {
    ElMessage.error('加载动作库失败，请确认后端已启动')
  } finally {
    loading.value = false
  }
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
        <el-table-column label="禁忌部位" min-width="130">
          <template #default="{ row }">
            <span v-if="row.contraindication" class="warn">{{ row.contraindication }}</span>
            <span v-else class="muted">无</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && filtered.length === 0" description="没有符合条件的动作，试试放宽筛选" />
    </el-card>
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
</style>
