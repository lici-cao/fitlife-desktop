<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useFoodStore, type FoodItem } from '../stores/food'
import { useUserStore } from '../stores/user'
import { useAppStore } from '../stores/app'
import { ElMessage } from 'element-plus'

const foodStore = useFoodStore()
const userStore = useUserStore()
const appStore = useAppStore()

// 初始化数据
onMounted(() => {
  userStore.initFromStorage()
})

const searchQuery = ref('')
const selectedCategory = ref<string>('')
const customFoodDialog = ref(false)
const portionDialog = ref(false)

const customFoodForm = ref({
  name: '',
  calories: 0,
  protein: 0,
  carbs: 0,
  fat: 0,
  servingSize: 100,
  unit: '克',
  category: 'common' as FoodItem['category']
})

// 自定义分量表单
const portionForm = ref({
  food: null as FoodItem | null,
  quantity: 100,
  unit: '克'
})

// 计算自定义分量的热量
const calculatedCalories = computed(() => {
  if (!portionForm.value.food) return 0
  const food = portionForm.value.food
  const baseQty = food.servingSize || 100
  return Math.round((food.calories / baseQty) * portionForm.value.quantity)
})

// 根据选择的日期获取摄入数据
const todayIntake = computed(() => {
  const record = userStore.dailyRecords.find(r => r.date === appStore.selectedDate)
  return record?.foodIntake || 0
})

const filteredFoods = computed(() => {
  let foods = foodStore.allFoods
  if (searchQuery.value) {
    foods = foodStore.searchFoods(searchQuery.value)
  }
  if (selectedCategory.value) {
    foods = foods.filter(f => f.category === selectedCategory.value)
  }
  return foods
})

const categoryOptions = [
  { label: '全部', value: '' },
  { label: '常见食物', value: 'common' },
  { label: '早餐', value: 'breakfast' },
  { label: '午餐', value: 'lunch' },
  { label: '晚餐', value: 'dinner' },
  { label: '零食', value: 'snack' },
  { label: '外食', value: 'restaurant' }
]

const addFood = (food: FoodItem) => {
  // 打开自定义分量对话框
  portionForm.value = {
    food: food,
    quantity: food.servingSize || 100,
    unit: food.unit || '克'
  }
  portionDialog.value = true
}

// 确认添加自定义分量的食物
const confirmAddFood = () => {
  if (!portionForm.value.food) return
  
  const food = portionForm.value.food
  const calories = calculatedCalories.value
  
  userStore.addFoodIntake(calories, appStore.selectedDate)
  ElMessage.success(`已添加 ${food.name} (${portionForm.value.quantity}${portionForm.value.unit}): ${calories} 千卡`)
  
  portionDialog.value = false
}

const addCustomFood = () => {
  foodStore.addCustomFood(customFoodForm.value)
  ElMessage.success('自定义食物已添加')
  customFoodDialog.value = false
  customFoodForm.value = {
    name: '',
    calories: 0,
    protein: 0,
    carbs: 0,
    fat: 0,
    servingSize: 100,
    unit: '克',
    category: 'common'
  }
}

const getCategoryTagType = (category: string) => {
  const types: Record<string, string> = {
    common: '',
    breakfast: 'warning',
    lunch: 'success',
    dinner: 'primary',
    snack: 'info',
    restaurant: 'danger'
  }
  return types[category] || ''
}

const getCategoryLabel = (category: string) => {
  const labels: Record<string, string> = {
    common: '常见',
    breakfast: '早餐',
    lunch: '午餐',
    dinner: '晚餐',
    snack: '零食',
    restaurant: '外食'
  }
  return labels[category] || category
}
</script>

<template>
  <div class="food-page">
    <el-row :gutter="20">
      <el-col :span="16">
        <el-card shadow="hover" class="search-card">
          <div class="search-header">
            <el-input
              v-model="searchQuery"
              placeholder="搜索食物..."
              size="large"
              clearable
              style="width: 300px"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-select v-model="selectedCategory" placeholder="分类" style="width: 150px">
              <el-option v-for="opt in categoryOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
            </el-select>
            <el-button type="primary" @click="customFoodDialog = true">
              <el-icon><Plus /></el-icon>
              添加自定义食物
            </el-button>
          </div>
        </el-card>

        <el-card shadow="hover" class="food-list-card">
          <el-table :data="filteredFoods" style="width: 100%" max-height="500">
            <el-table-column prop="name" label="食物名称" width="150" />
            <el-table-column label="分类" width="100">
              <template #default="{ row }">
                <el-tag :type="getCategoryTagType(row.category)" size="small">
                  {{ getCategoryLabel(row.category) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="calories" label="热量" width="100">
              <template #default="{ row }">
                <span class="calorie-value">{{ row.calories }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="protein" label="蛋白质(g)" width="100" />
            <el-table-column prop="carbs" label="碳水(g)" width="100" />
            <el-table-column prop="fat" label="脂肪(g)" width="100" />
            <el-table-column label="份量" width="120">
              <template #default="{ row }">
                {{ row.servingSize }}{{ row.unit }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" size="small" @click="addFood(row)">
                  添加
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card shadow="hover" class="today-summary">
          <template #header>
            <span>今日摄入</span>
          </template>
          <div class="summary-main">
            <div class="summary-value">{{ todayIntake }}</div>
            <div class="summary-unit">千卡</div>
          </div>
          <div class="summary-progress">
            <el-progress
              :percentage="Math.round((todayIntake / userStore.targetCalories) * 100)"
              :color="todayIntake > userStore.targetCalories ? '#f56c6c' : '#409eff'"
              :show-text="true"
            />
          </div>
          <div class="summary-target">
            目标: {{ userStore.targetCalories }} 千卡
          </div>
        </el-card>

        <el-card shadow="hover" class="quick-add">
          <template #header>
            <span>快速添加</span>
          </template>
          <div class="quick-grid">
            <div v-for="food in foodStore.foodDatabase.slice(0, 6)" :key="food.id" class="quick-item" @click="addFood(food)">
              <div class="quick-name">{{ food.name }}</div>
              <div class="quick-cal">{{ food.calories }}千卡</div>
            </div>
          </div>
        </el-card>

        <el-card shadow="hover" class="tips-card">
          <template #header>
            <span>外食热量参考</span>
          </template>
          <div class="restaurant-list">
            <div v-for="food in foodStore.getFoodsByCategory('restaurant')" :key="food.id" class="restaurant-item" @click="addFood(food)">
              <span>{{ food.name }}</span>
              <span class="calorie-badge">{{ food.calories }}千卡</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="customFoodDialog" title="添加自定义食物" width="500px">
      <el-form :model="customFoodForm" label-width="100px">
        <el-form-item label="食物名称">
          <el-input v-model="customFoodForm.name" />
        </el-form-item>
        <el-form-item label="热量">
          <el-input-number v-model="customFoodForm.calories" :min="0" />
          <span style="margin-left: 10px">千卡/份</span>
        </el-form-item>
        <el-form-item label="份量">
          <el-input-number v-model="customFoodForm.servingSize" :min="1" />
          <el-select v-model="customFoodForm.unit" style="width: 100px; margin-left: 10px">
            <el-option label="克" value="克" />
            <el-option label="毫升" value="毫升" />
            <el-option label="个" value="个" />
            <el-option label="份" value="份" />
            <el-option label="杯" value="杯" />
          </el-select>
        </el-form-item>
        <el-form-item label="蛋白质">
          <el-input-number v-model="customFoodForm.protein" :min="0" />
          <span style="margin-left: 10px">克</span>
        </el-form-item>
        <el-form-item label="碳水化合物">
          <el-input-number v-model="customFoodForm.carbs" :min="0" />
          <span style="margin-left: 10px">克</span>
        </el-form-item>
        <el-form-item label="脂肪">
          <el-input-number v-model="customFoodForm.fat" :min="0" />
          <span style="margin-left: 10px">克</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="customFoodDialog = false">取消</el-button>
        <el-button type="primary" @click="addCustomFood">添加</el-button>
      </template>
    </el-dialog>

    <!-- 自定义分量对话框 -->
    <el-dialog v-model="portionDialog" title="添加食物" width="400px">
      <div v-if="portionForm.food" class="portion-dialog">
        <div class="food-info">
          <h4>{{ portionForm.food.name }}</h4>
          <p class="original-info">
            默认份量: {{ portionForm.food.servingSize }}{{ portionForm.food.unit }} 
            = {{ portionForm.food.calories }} 千卡
          </p>
        </div>
        
        <el-form :model="portionForm" label-width="80px" style="margin-top: 20px">
          <el-form-item label="份量">
            <el-input-number 
              v-model="portionForm.quantity" 
              :min="1" 
              :max="10000"
              :step="1"
              size="large"
              style="width: 150px"
            />
            <el-select v-model="portionForm.unit" style="width: 100px; margin-left: 10px">
              <el-option label="克" value="克" />
              <el-option label="毫升" value="毫升" />
              <el-option label="个" value="个" />
              <el-option label="份" value="份" />
              <el-option label="杯" value="杯" />
              <el-option label="勺" value="勺" />
              <el-option label="碗" value="碗" />
            </el-select>
          </el-form-item>
          
          <el-form-item label="计算结果">
            <div class="result-box">
              <span class="result-label">本次摄入热量:</span>
              <span class="result-value">{{ calculatedCalories }}</span>
              <span class="result-unit">千卡</span>
            </div>
          </el-form-item>
        </el-form>
      </div>
      
      <template #footer>
        <el-button @click="portionDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmAddFood">确认添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.food-page {
  padding: 0;
}

.search-card {
  border-radius: 12px;
  margin-bottom: 20px;
}

.search-header {
  display: flex;
  gap: 15px;
  align-items: center;
}

.food-list-card {
  border-radius: 12px;
}

.calorie-value {
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
  color: #409eff;
}

.summary-unit {
  color: #909399;
}

.summary-progress {
  margin-bottom: 10px;
}

.summary-target {
  color: #909399;
  font-size: 14px;
}

.quick-add {
  border-radius: 12px;
  margin-bottom: 20px;
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
}

.quick-item {
  padding: 15px;
  background: #f5f7fa;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.3s;
}

.quick-item:hover {
  background: #e6e6e6;
  transform: translateY(-2px);
}

.quick-name {
  font-size: 14px;
  color: #303133;
}

.quick-cal {
  font-size: 12px;
  color: #f56c6c;
  margin-top: 5px;
}

.tips-card {
  border-radius: 12px;
}

.restaurant-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.restaurant-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px;
  background: #f5f7fa;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.3s;
}

.restaurant-item:hover {
  background: #e6e6e6;
}

.calorie-badge {
  background: #f56c6c;
  color: #fff;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
}

/* 自定义分量对话框样式 */
.portion-dialog {
  padding: 10px;
}

.food-info {
  padding: 15px;
  background: #f9fafc;
  border-radius: 8px;
}

.food-info h4 {
  margin: 0 0 10px 0;
  color: #303133;
  font-size: 16px;
}

.original-info {
  margin: 0;
  color: #909399;
  font-size: 14px;
}

.result-box {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 15px;
  background: #e8f4fd;
  border-radius: 8px;
}

.result-label {
  color: #606266;
  font-size: 14px;
}

.result-value {
  font-size: 32px;
  font-weight: bold;
  color: #f56c6c;
}

.result-unit {
  color: #909399;
  font-size: 14px;
}
</style>