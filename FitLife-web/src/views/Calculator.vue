<script setup lang="ts">
import { ref, computed } from 'vue'
import { useUserStore } from '../stores/user'

const userStore = useUserStore()

const weight = ref(userStore.profile.weight)
const targetWeight = ref(userStore.profile.weight - 5)
const weeklyLoss = ref(0.5)

const weeks = ref(12)

const bmrResult = computed(() => userStore.bmr)

const tdeeResult = computed(() => userStore.tdee)

const targetCalories = computed(() => {
  const weightDiff = (userStore.profile.weight - targetWeight.value) * 7700
  const weeklyCalorieDeficit = weeklyLoss.value * 7700
  const totalDeficit = weightDiff + weeklyCalorieDeficit * 2
  const dailyDeficit = Math.round(totalDeficit / (weeks.value * 7))
  return Math.max(1200, userStore.tdee - dailyDeficit)
})

const dailyDeficit = computed(() => userStore.tdee - targetCalories.value)

const proteinNeed = computed(() => {
  return Math.round(weight.value * 2.2)
})

const waterNeed = computed(() => {
  return Math.round(weight.value * 30)
})

const proteinCalories = computed(() => proteinNeed.value * 4)
const fatCalories = computed(() => Math.round(targetCalories.value * 0.25))
const carbsCalories = computed(() => targetCalories.value - proteinCalories.value - fatCalories.value)

const proteinGrams = computed(() => Math.round(proteinCalories.value / 4))
const carbsGrams = computed(() => Math.round(carbsCalories.value / 4))
const fatGrams = computed(() => Math.round(fatCalories.value / 9))

const macroPercentages = computed(() => ({
  protein: Math.round((proteinCalories.value / targetCalories.value) * 100),
  carbs: Math.round((carbsCalories.value / targetCalories.value) * 100),
  fat: Math.round((fatCalories.value / targetCalories.value) * 100)
}))
</script>

<template>
  <div class="calculator-page">
    <el-row :gutter="20">
      <el-col :span="12">
        <el-card shadow="hover" class="calc-card">
          <template #header>
            <div class="card-header">
              <el-icon size="20"><Odometer /></el-icon>
              <span>热量计算器</span>
            </div>
          </template>
          <el-form label-position="top">
            <el-form-item label="当前体重 (kg)">
              <el-slider v-model="weight" :min="30" :max="200" :show-input="true" :step="0.1" input-size="large" />
            </el-form-item>
            <el-form-item label="目标体重 (kg)">
              <el-slider v-model="targetWeight" :min="30" :max="200" :show-input="true" :step="0.1" input-size="large" />
            </el-form-item>
            <el-form-item label="每周目标减重 (kg)">
              <el-slider v-model="weeklyLoss" :min="0.25" :max="1.5" :step="0.25" :show-input="true" input-size="large" />
              <div class="slider-labels">
                <span>0.25kg</span>
                <span>0.5kg</span>
                <span>1kg</span>
                <span>1.5kg</span>
              </div>
            </el-form-item>
            <el-form-item label="计划周期 (周)">
              <el-slider v-model="weeks" :min="4" :max="52" :show-input="true" input-size="large" />
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="hover" class="result-card">
          <template #header>
            <div class="card-header">
              <el-icon size="20"><DataAnalysis /></el-icon>
              <span>计算结果</span>
            </div>
          </template>
          <div class="result-main">
            <div class="result-label">每日目标摄入</div>
            <div class="result-value">{{ targetCalories }}<span class="unit">千卡</span></div>
            <div class="result-sub">
              每日缺口: <span class="deficit">{{ dailyDeficit }}</span> 千卡
            </div>
          </div>

          <el-divider />

          <div class="metabolism-info">
            <div class="meta-item">
              <div class="meta-label">基础代谢 (BMR)</div>
              <div class="meta-value">{{ bmrResult.toFixed(0) }}</div>
            </div>
            <div class="meta-item">
              <div class="meta-label">总消耗 (TDEE)</div>
              <div class="meta-value">{{ tdeeResult }}</div>
            </div>
          </div>

          <el-divider />

          <div class="macro-section">
            <div class="macro-title">营养素分配</div>
            <el-progress type="circle" :percentage="macroPercentages.protein" :width="80" color="#67c23a">
              <template #default>
                <div class="macro-circle">
                  <div class="macro-percent"> {{ macroPercentages.protein }}%</div>
                  <div class="macro-name">蛋白质</div>
                </div>
              </template>
            </el-progress>
            <el-progress type="circle" :percentage="macroPercentages.carbs" :width="80" color="#e6a23c">
              <template #default>
                <div class="macro-circle">
                  <div class="macro-percent">{{ macroPercentages.carbs }}%</div>
                  <div class="macro-name">碳水</div>
                </div>
              </template>
            </el-progress>
            <el-progress type="circle" :percentage="macroPercentages.fat" :width="80" color="#909399">
              <template #default>
                <div class="macro-circle">
                  <div class="macro-percent">{{ macroPercentages.fat }}%</div>
                  <div class="macro-name">脂肪</div>
                </div>
              </template>
            </el-progress>
          </div>

          <div class="macro-details">
            <div class="macro-detail-item">
              <span class="dot protein"></span>
              蛋白质: {{ proteinGrams }}g ({{ proteinCalories }}千卡)
            </div>
            <div class="macro-detail-item">
              <span class="dot carbs"></span>
              碳水: {{ carbsGrams }}g ({{ carbsCalories }}千卡)
            </div>
            <div class="macro-detail-item">
              <span class="dot fat"></span>
              脂肪: {{ fatGrams }}g ({{ fatCalories }}千卡)
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="8">
        <el-card shadow="hover" class="tips-card">
          <template #header>
            <span>蛋白质建议</span>
          </template>
          <div class="tip-content">
            <div class="tip-value">{{ proteinNeed }}g</div>
            <div class="tip-desc">每日蛋白质摄入量</div>
            <el-divider />
            <div class="tip-list">
              <div class="tip-item">鸡胸肉 100g = 31g 蛋白质</div>
              <div class="tip-item">鸡蛋 1个 = 6g 蛋白质</div>
              <div class="tip-item">豆腐 100g = 8g 蛋白质</div>
              <div class="tip-item">希腊酸奶 100g = 10g 蛋白质</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="tips-card">
          <template #header>
            <span>饮水建议</span>
          </template>
          <div class="tip-content">
            <div class="tip-value">{{ waterNeed }}ml</div>
            <div class="tip-desc">每日建议饮水量</div>
            <el-divider />
            <div class="tip-list">
              <div class="tip-item">早起空腹喝 300ml</div>
              <div class="tip-item">运动前30分钟 200ml</div>
              <div class="tip-item">运动后补充 300ml</div>
              <div class="tip-item">睡前1小时 200ml</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="tips-card">
          <template #header>
            <span>减脂小贴士</span>
          </template>
          <div class="tip-content">
            <el-steps direction="vertical" :space="60" :active="4">
              <el-step title="记录饮食" description="使用食物记录功能" />
              <el-step title="按时吃饭" description="不要跳过早餐" />
              <el-step title="多喝水" description="每天2-3升水" />
              <el-step title="保证睡眠" description="每天7-8小时" />
              <el-step title="适度运动" description="结合有氧和力量" />
            </el-steps>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.calculator-page {
  padding: 0;
}

.calc-card, .result-card, .tips-card {
  border-radius: 12px;
  height: 100%;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.slider-labels {
  display: flex;
  justify-content: space-between;
  color: #c0c4cc;
  font-size: 12px;
  margin-top: 5px;
}

.result-main {
  text-align: center;
  padding: 20px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 12px;
  color: #fff;
  margin-bottom: 20px;
}

.result-label {
  font-size: 14px;
  opacity: 0.9;
}

.result-value {
  font-size: 48px;
  font-weight: bold;
}

.result-value .unit {
  font-size: 16px;
  opacity: 0.8;
}

.result-sub {
  font-size: 14px;
  margin-top: 5px;
}

.deficit {
  font-weight: bold;
  color: #ffd700;
}

.metabolism-info {
  display: flex;
  justify-content: space-around;
}

.meta-item {
  text-align: center;
}

.meta-label {
  font-size: 12px;
  color: #909399;
}

.meta-value {
  font-size: 24px;
  font-weight: bold;
  color: #303133;
}

.macro-section {
  display: flex;
  justify-content: space-around;
  margin-bottom: 20px;
}

.macro-circle {
  text-align: center;
}

.macro-percent {
  font-size: 16px;
  font-weight: bold;
}

.macro-name {
  font-size: 12px;
  color: #909399;
}

.macro-details {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.macro-detail-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dot.protein { background: #67c23a; }
.dot.carbs { background: #e6a23c; }
.dot.fat { background: #909399; }

.tip-content {
  text-align: center;
}

.tip-value {
  font-size: 36px;
  font-weight: bold;
  color: #409eff;
}

.tip-desc {
  color: #909399;
  font-size: 14px;
}

.tip-list {
  text-align: left;
  font-size: 13px;
  color: #606266;
}

.tip-item {
  padding: 5px 0;
}
</style>