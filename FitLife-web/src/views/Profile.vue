<script setup lang="ts">
import { reactive, computed } from 'vue'
import { useUserStore, type UserProfile } from '../stores/user'
import { ElMessage } from 'element-plus'

const userStore = useUserStore()

const profileForm = reactive<UserProfile>({ ...userStore.profile })

const activityOptions = [
  { label: '久坐（很少运动）', value: 'sedentary' },
  { label: '轻度活跃（每周1-3天运动）', value: 'light' },
  { label: '中等活跃（每周3-5天运动）', value: 'moderate' },
  { label: '非常活跃（每周6-7天运动）', value: 'active' },
  { label: '超级活跃（体力劳动者）', value: 'very_active' }
]

const goalOptions = [
  { label: '减脂', value: 'fat_loss', color: '#67c23a' },
  { label: '保持体重', value: 'maintain', color: '#409eff' },
  { label: '增肌', value: 'muscle_gain', color: '#f56c6c' }
]

const equipmentOptions = ['徒手', '哑铃', '杠铃', '器械', '弹力带', '壶铃']
const injuryOptions = ['膝关节', '腰椎', '肩关节', '腕关节']

// 器械/禁忌以数组绑定，提交时拼成逗号串
const equipmentSelected = computed({
  get: () => (profileForm.equipments ? profileForm.equipments.split(',').filter(Boolean) : []),
  set: (v: string[]) => { profileForm.equipments = v.join(',') }
})
const injurySelected = computed({
  get: () => (profileForm.injuries ? profileForm.injuries.split(',').filter(Boolean) : []),
  set: (v: string[]) => { profileForm.injuries = v.join(',') }
})

// 补默认值，避免旧数据为空导致 slider 报错
if (profileForm.experience === undefined || profileForm.experience === null) profileForm.experience = '初级'
if (profileForm.weeklyDays === undefined || profileForm.weeklyDays === null) profileForm.weeklyDays = 3
if (profileForm.sessionMinutes === undefined || profileForm.sessionMinutes === null) profileForm.sessionMinutes = 45
if (!profileForm.equipments) profileForm.equipments = '徒手'
if (!profileForm.injuries) profileForm.injuries = ''

const handleSubmit = () => {
  userStore.updateProfile(profileForm)
  ElMessage.success('个人信息已更新')
}

const bmiCategory = computed(() => {
  const bmi = userStore.bmi
  if (bmi < 18.5) return { text: '偏瘦', type: 'info' }
  if (bmi < 24) return { text: '正常', type: 'success' }
  if (bmi < 28) return { text: '超重', type: 'warning' }
  return { text: '肥胖', type: 'danger' }
})
</script>

<template>
  <div class="profile-page">
    <el-row :gutter="20">
      <el-col :span="16">
        <el-card shadow="hover" class="form-card">
          <template #header>
            <span>基本信息</span>
          </template>
          <el-form :model="profileForm" label-width="100px">
            <el-form-item label="姓名">
              <el-input v-model="profileForm.name" placeholder="请输入姓名" />
            </el-form-item>
            <el-form-item label="性别">
              <el-radio-group v-model="profileForm.gender">
                <el-radio value="female">女</el-radio>
                <el-radio value="male">男</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="年龄">
              <el-input-number v-model="profileForm.age" :min="10" :max="100" />
              <span class="form-hint">岁</span>
            </el-form-item>
            <el-form-item label="身高">
              <el-input-number v-model="profileForm.height" :min="100" :max="250" />
              <span class="form-hint">厘米</span>
            </el-form-item>
            <el-form-item label="体重">
              <el-input-number v-model="profileForm.weight" :min="30" :max="300" :precision="1" />
              <span class="form-hint">公斤</span>
            </el-form-item>
            <el-form-item label="体脂率">
              <el-slider v-model="profileForm.bodyFat" :min="5" :max="60" :show-input="true" input-size="small" />
              <span class="form-hint">%</span>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card shadow="hover" class="form-card">
          <template #header>
            <span>运动目标</span>
          </template>
          <el-form label-width="100px">
            <el-form-item label="活动水平">
              <el-select v-model="profileForm.activityLevel" placeholder="请选择活动水平" style="width: 100%">
                <el-option v-for="opt in activityOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="健身目标">
              <el-radio-group v-model="profileForm.goal" class="goal-radio-group">
                <el-radio v-for="opt in goalOptions" :key="opt.value" :value="opt.value">
                  <span class="goal-option">
                    <el-icon v-if="profileForm.goal === opt.value" color="#00d9ff"><Select /></el-icon>
                    {{ opt.label }}
                  </span>
                </el-radio>
              </el-radio-group>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card shadow="hover" class="form-card">
          <template #header>
            <span>训练计划偏好</span>
          </template>
          <el-form label-width="100px">
            <el-form-item label="运动经验">
              <el-select v-model="profileForm.experience" placeholder="请选择运动经验" style="width: 100%">
                <el-option label="初级（刚开始锻炼）" value="初级" />
                <el-option label="中级（有一年以上经验）" value="中级" />
                <el-option label="高级（长期系统训练）" value="高级" />
              </el-select>
            </el-form-item>
            <el-form-item label="每周天数">
              <el-slider v-model="profileForm.weeklyDays" :min="1" :max="7" show-stops />
              <span class="form-hint">天/周</span>
            </el-form-item>
            <el-form-item label="单次时长">
              <el-slider v-model="profileForm.sessionMinutes" :min="15" :max="120" :step="15" show-stops />
              <span class="form-hint">分钟</span>
            </el-form-item>
            <el-form-item label="可用器械">
              <el-checkbox-group v-model="equipmentSelected">
                <el-checkbox v-for="eq in equipmentOptions" :key="eq" :value="eq" :label="eq" />
              </el-checkbox-group>
            </el-form-item>
            <el-form-item label="伤病/禁忌">
              <el-checkbox-group v-model="injurySelected">
                <el-checkbox v-for="inj in injuryOptions" :key="inj" :value="inj" :label="inj" />
              </el-checkbox-group>
              <div class="form-tip">勾选后，生成计划时会自动排除相关禁忌动作</div>
            </el-form-item>
          </el-form>
        </el-card>

        <el-button type="primary" size="large" class="submit-btn" @click="handleSubmit">
          保存修改
        </el-button>
      </el-col>

      <el-col :span="8">
        <el-card shadow="hover" class="summary-card">
          <template #header>
            <span>数据概览</span>
          </template>
          <div class="summary-item">
            <div class="summary-label">基础代谢率 (BMR)</div>
            <div class="summary-value">{{ userStore.bmr.toFixed(0) }} <span class="unit">千卡/天</span></div>
          </div>
          <el-divider />
          <div class="summary-item">
            <div class="summary-label">总体代谢 (TDEE)</div>
            <div class="summary-value">{{ userStore.tdee }} <span class="unit">千卡/天</span></div>
          </div>
          <el-divider />
          <div class="summary-item">
            <div class="summary-label">每日目标</div>
            <div class="summary-value target">{{ userStore.targetCalories }} <span class="unit">千卡/天</span></div>
          </div>
          <el-divider />
          <div class="summary-item">
            <div class="summary-label">身体质量指数 (BMI)</div>
            <div class="summary-value">
              {{ userStore.bmi.toFixed(1) }}
              <el-tag :type="bmiCategory.type" size="small">{{ bmiCategory.text }}</el-tag>
            </div>
          </div>
        </el-card>

        <el-card shadow="hover" class="macro-card">
          <template #header>
            <span>营养素建议</span>
          </template>
          <div class="macro-item">
            <div class="macro-label">
              <el-icon color="#67c23a"><Chicken /></el-icon>
              蛋白质
            </div>
            <div class="macro-value">{{ userStore.recommendedProtein }}g/天</div>
            <div class="macro-hint">帮助维持肌肉量</div>
          </div>
          <div class="macro-item">
            <div class="macro-label">
              <el-icon color="#e6a23c"><Sugar /></el-icon>
              碳水化合物
            </div>
            <div class="macro-value">{{ userStore.recommendedCarbs }}g/天</div>
            <div class="macro-hint">提供运动能量</div>
          </div>
          <div class="macro-item">
            <div class="macro-label">
              <el-icon color="#909399"><Cooking /></el-icon>
              脂肪
            </div>
            <div class="macro-value">{{ userStore.recommendedFat }}g/天</div>
            <div class="macro-hint">维持激素平衡</div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.profile-page {
  padding: 0;
}

.form-card {
  border-radius: 12px;
  margin-bottom: 20px;
}

.form-hint {
  margin-left: 10px;
  color: #909399;
}

.goal-radio-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.goal-option {
  display: flex;
  align-items: center;
  gap: 5px;
}

.submit-btn {
  width: 100%;
  border-radius: 25px;
  background: linear-gradient(135deg, #00d9ff 0%, #007bff 100%);
  border: none;
}

.summary-card {
  border-radius: 12px;
  margin-bottom: 20px;
}

.summary-item {
  padding: 10px 0;
}

.summary-label {
  color: #909399;
  font-size: 14px;
  margin-bottom: 8px;
}

.summary-value {
  font-size: 24px;
  font-weight: bold;
  color: #303133;
  display: flex;
  align-items: center;
  gap: 10px;
}

.summary-value .unit {
  font-size: 12px;
  color: #909399;
  font-weight: normal;
}

.summary-value.target {
  color: #00d9ff;
}

.macro-card {
  border-radius: 12px;
}

.macro-item {
  padding: 15px;
  background: #f5f7fa;
  border-radius: 8px;
  margin-bottom: 10px;
}

.macro-item:last-child {
  margin-bottom: 0;
}

.macro-label {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #606266;
  font-size: 14px;
  margin-bottom: 5px;
}

.macro-value {
  font-size: 18px;
  font-weight: bold;
  color: #303133;
}

.macro-hint {
  font-size: 12px;
  color: #c0c4cc;
  margin-top: 3px;
}
</style>