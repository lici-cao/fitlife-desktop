import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export interface FoodItem {
  id: string
  name: string
  calories: number
  protein: number
  carbs: number
  fat: number
  servingSize: number
  unit: string
  category: 'breakfast' | 'lunch' | 'dinner' | 'snack' | 'common' | 'restaurant'
}

export const useFoodStore = defineStore('food', () => {
  const foodDatabase: FoodItem[] = [
    { id: '1', name: '鸡胸肉', calories: 133, protein: 31, carbs: 0, fat: 1, servingSize: 100, unit: '克', category: 'common' },
    { id: '2', name: '鸡蛋', calories: 155, protein: 13, carbs: 1, fat: 11, servingSize: 100, unit: '克', category: 'common' },
    { id: '3', name: '糙米饭', calories: 123, protein: 3, carbs: 26, fat: 1, servingSize: 100, unit: '克', category: 'common' },
    { id: '4', name: '西兰花', calories: 34, protein: 3, carbs: 7, fat: 0, servingSize: 100, unit: '克', category: 'common' },
    { id: '5', name: '三文鱼', calories: 208, protein: 20, carbs: 0, fat: 13, servingSize: 100, unit: '克', category: 'common' },
    { id: '6', name: '全麦面包', calories: 247, protein: 13, carbs: 41, fat: 4, servingSize: 100, unit: '克', category: 'common' },
    { id: '7', name: '牛奶', calories: 42, protein: 3, carbs: 5, fat: 1, servingSize: 100, unit: '毫升', category: 'common' },
    { id: '8', name: '香蕉', calories: 93, protein: 1, carbs: 23, fat: 0, servingSize: 100, unit: '克', category: 'common' },
    { id: '9', name: '苹果', calories: 52, protein: 0, carbs: 14, fat: 0, servingSize: 100, unit: '克', category: 'common' },
    { id: '10', name: '牛油果', calories: 160, protein: 2, carbs: 9, fat: 15, servingSize: 100, unit: '克', category: 'common' },
    { id: '11', name: '希腊酸奶', calories: 97, protein: 9, carbs: 4, fat: 5, servingSize: 100, unit: '克', category: 'common' },
    { id: '12', name: '杏仁', calories: 579, protein: 21, carbs: 22, fat: 50, servingSize: 100, unit: '克', category: 'common' },
    { id: '13', name: '红薯', calories: 86, protein: 1, carbs: 20, fat: 0, servingSize: 100, unit: '克', category: 'common' },
    { id: '14', name: '菠菜', calories: 23, protein: 3, carbs: 4, fat: 0, servingSize: 100, unit: '克', category: 'common' },
    { id: '15', name: '鸡腿肉', calories: 209, protein: 26, carbs: 0, fat: 11, servingSize: 100, unit: '克', category: 'common' },
    { id: '16', name: '米饭', calories: 130, protein: 3, carbs: 28, fat: 0, servingSize: 100, unit: '克', category: 'common' },
    { id: '17', name: '面条', calories: 138, protein: 4, carbs: 25, fat: 2, servingSize: 100, unit: '克', category: 'common' },
    { id: '18', name: '豆腐', calories: 76, protein: 8, carbs: 2, fat: 4, servingSize: 100, unit: '克', category: 'common' },
    { id: '19', name: '豆浆', calories: 33, protein: 3, carbs: 2, fat: 2, servingSize: 100, unit: '毫升', category: 'common' },
    { id: '20', name: '燕麦', calories: 389, protein: 17, carbs: 66, fat: 7, servingSize: 100, unit: '克', category: 'common' },
    { id: '21', name: '麦当劳巨无霸', calories: 563, protein: 26, carbs: 44, fat: 33, servingSize: 1, unit: '个', category: 'restaurant' },
    { id: '22', name: '麦当劳薯条(中)', calories: 320, protein: 5, carbs: 42, fat: 15, servingSize: 1, unit: '份', category: 'restaurant' },
    { id: '23', name: '肯德基原味鸡', calories: 298, protein: 24, carbs: 10, fat: 18, servingSize: 1, unit: '块', category: 'restaurant' },
    { id: '24', name: '星巴克拿铁', calories: 190, protein: 10, carbs: 18, fat: 6, servingSize: 1, unit: '杯', category: 'restaurant' },
    { id: '25', name: '海底捞火锅(人均)', calories: 850, protein: 45, carbs: 60, fat: 50, servingSize: 1, unit: '人份', category: 'restaurant' },
    { id: '26', name: '沙县小吃炒饭', calories: 480, protein: 15, carbs: 65, fat: 18, servingSize: 1, unit: '份', category: 'restaurant' },
    { id: '27', name: '兰州拉面', calories: 420, protein: 18, carbs: 55, fat: 15, servingSize: 1, unit: '碗', category: 'restaurant' },
    { id: '28', name: '黄焖鸡米饭', calories: 550, protein: 25, carbs: 60, fat: 22, servingSize: 1, unit: '份', category: 'restaurant' },
    { id: '29', name: '赛百味三明治', calories: 350, protein: 20, carbs: 40, fat: 12, servingSize: 1, unit: '个', category: 'restaurant' },
    { id: '30', name: '披萨(寸)', calories: 272, protein: 12, carbs: 33, fat: 10, servingSize: 100, unit: '克', category: 'restaurant' },
    { id: '31', name: '包子(肉)', calories: 120, protein: 6, carbs: 15, fat: 4, servingSize: 1, unit: '个', category: 'common' },
    { id: '32', name: '油条', calories: 386, protein: 6, carbs: 51, fat: 17, servingSize: 100, unit: '克', category: 'common' },
    { id: '33', name: '豆浆', calories: 45, protein: 4, carbs: 3, fat: 2, servingSize: 100, unit: '毫升', category: 'breakfast' },
    { id: '34', name: '白粥', calories: 46, protein: 1, carbs: 10, fat: 0, servingSize: 100, unit: '克', category: 'breakfast' },
    { id: '35', name: '玉米', calories: 112, protein: 4, carbs: 22, fat: 1, servingSize: 100, unit: '克', category: 'breakfast' }
  ]

  const customFoods = ref<FoodItem[]>([])

  const allFoods = computed(() => [...foodDatabase, ...customFoods.value])

  const getFoodById = (id: string) => {
    return allFoods.value.find(f => f.id === id)
  }

  const searchFoods = (query: string) => {
    if (!query) return allFoods.value
    return allFoods.value.filter(f => 
      f.name.toLowerCase().includes(query.toLowerCase())
    )
  }

  const getFoodsByCategory = (category: FoodItem['category']) => {
    return allFoods.value.filter(f => f.category === category)
  }

  const addCustomFood = (food: Omit<FoodItem, 'id'>) => {
    const newFood: FoodItem = {
      ...food,
      id: Date.now().toString()
    }
    customFoods.value.push(newFood)
  }

  const removeCustomFood = (id: string) => {
    const index = customFoods.value.findIndex(f => f.id === id)
    if (index > -1) {
      customFoods.value.splice(index, 1)
    }
  }

  return {
    foodDatabase,
    customFoods,
    allFoods,
    getFoodById,
    searchFoods,
    getFoodsByCategory,
    addCustomFood,
    removeCustomFood
  }
})