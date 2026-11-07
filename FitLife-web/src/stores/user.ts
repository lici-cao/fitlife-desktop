import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'

export interface UserProfile {
  name: string
  gender: 'male' | 'female'
  age: number
  height: number
  weight: number
  bodyFat: number
  goal: 'fat_loss' | 'maintain' | 'muscle_gain'
  activityLevel: 'sedentary' | 'light' | 'moderate' | 'active' | 'very_active'
  /** 运动经验：初级 / 中级 / 高级 */
  experience?: string
  /** 每周可训练天数 */
  weeklyDays?: number
  /** 单次训练时长（分钟） */
  sessionMinutes?: number
  /** 可用器械，逗号分隔 */
  equipments?: string
  /** 伤病史/禁忌部位，逗号分隔 */
  injuries?: string
}

export interface DailyRecord {
  date: string
  foodIntake: number
  exerciseBurn: number
  targetCalories: number
  weight?: number
}

const STORAGE_KEY_PROFILE = 'fitlife_profile'
const STORAGE_KEY_RECORDS = 'fitlife_dailyRecords'

export const useUserStore = defineStore('user', () => {
  const profile = ref<UserProfile>({
    name: '',
    gender: 'female',
    age: 25,
    height: 165,
    weight: 60,
    bodyFat: 25,
    goal: 'fat_loss',
    activityLevel: 'moderate',
    experience: '初级',
    weeklyDays: 3,
    sessionMinutes: 45,
    equipments: '徒手',
    injuries: ''
  })

  const dailyRecords = ref<DailyRecord[]>([])

  // 监听数据变化，自动保存到 localStorage
  watch(profile, (newProfile) => {
    saveProfileToStorage(newProfile)
  }, { deep: true })

  watch(dailyRecords, (newRecords) => {
    saveRecordsToStorage(newRecords)
  }, { deep: true })

  // 保存用户档案到 localStorage
  const saveProfileToStorage = (data: UserProfile) => {
    try {
      localStorage.setItem(STORAGE_KEY_PROFILE, JSON.stringify(data))
    } catch (error) {
      console.error('保存用户档案失败:', error)
    }
  }

  // 保存每日记录到 localStorage
  const saveRecordsToStorage = (data: DailyRecord[]) => {
    try {
      localStorage.setItem(STORAGE_KEY_RECORDS, JSON.stringify(data))
    } catch (error) {
      console.error('保存每日记录失败:', error)
    }
  }

  // 从 localStorage 加载用户档案
  const loadProfileFromStorage = (): UserProfile | null => {
    try {
      const data = localStorage.getItem(STORAGE_KEY_PROFILE)
      if (data) {
        return JSON.parse(data)
      }
    } catch (error) {
      console.error('加载用户档案失败:', error)
    }
    return null
  }

  // 从 localStorage 加载每日记录
  const loadRecordsFromStorage = (): DailyRecord[] | null => {
    try {
      const data = localStorage.getItem(STORAGE_KEY_RECORDS)
      if (data) {
        return JSON.parse(data)
      }
    } catch (error) {
      console.error('加载每日记录失败:', error)
    }
    return null
  }

  // 初始化数据 - 从 localStorage 恢复
  const initFromStorage = () => {
    const savedProfile = loadProfileFromStorage()
    if (savedProfile) {
      profile.value = savedProfile
    }

    const savedRecords = loadRecordsFromStorage()
    if (savedRecords) {
      dailyRecords.value = savedRecords
    }
  }

  const activityMultipliers = {
    sedentary: 1.2,
    light: 1.375,
    moderate: 1.55,
    active: 1.725,
    very_active: 1.9
  }

  const bmr = computed(() => {
    const { gender, weight, height, age } = profile.value
    if (gender === 'female') {
      return 655 + 9.6 * weight + 1.8 * height - 4.7 * age
    }
    return 67 + 13.7 * weight + 5 * height - 6.8 * age
  })

  const bmi = computed(() => {
    const heightM = profile.value.height / 100
    return profile.value.weight / (heightM * heightM)
  })

  const tdee = computed(() => {
    return Math.round(bmr.value * activityMultipliers[profile.value.activityLevel])
  })

  const targetCalories = computed(() => {
    const deficit = profile.value.goal === 'fat_loss' ? 500 
      : profile.value.goal === 'muscle_gain' ? 300 : 0
    return profile.value.goal === 'fat_loss' 
      ? Math.round(tdee.value - deficit)
      : profile.value.goal === 'muscle_gain'
        ? Math.round(tdee.value + deficit)
        : tdee.value
  })

  const recommendedProtein = computed(() => {
    const leanMass = profile.value.weight * (1 - profile.value.bodyFat / 100)
    return Math.round(leanMass * 2.2)
  })

  const recommendedFat = computed(() => {
    return Math.round(targetCalories.value * 0.25 / 9)
  })

  const recommendedCarbs = computed(() => {
    return Math.round((targetCalories.value - recommendedProtein.value * 4 - recommendedFat.value * 9) / 4)
  })

  const todayRecord = computed(() => {
    const today = new Date().toISOString().split('T')[0]
    return dailyRecords.value.find(r => r.date === today) || {
      date: today,
      foodIntake: 0,
      exerciseBurn: 0,
      targetCalories: targetCalories.value
    }
  })

  const calorieDeficit = computed(() => {
    return todayRecord.value.targetCalories - todayRecord.value.foodIntake + todayRecord.value.exerciseBurn
  })

  const getWeeklyDeficit = () => {
    const today = new Date()
    const weekData = []
    
    for (let i = 6; i >= 0; i--) {
      const date = new Date(today)
      date.setDate(date.getDate() - i)
      const dateStr = date.toISOString().split('T')[0]
      
      const record = dailyRecords.value.find(r => r.date === dateStr)
      const deficit = record 
        ? record.targetCalories - record.foodIntake + record.exerciseBurn
        : targetCalories.value // 没有记录时，缺口等于目标热量
      
      weekData.push({
        date: dateStr,
        deficit: deficit
      })
    }
    
    return weekData
  }

  const addFoodIntake = (calories: number, date?: string) => {
    const targetDate = date || new Date().toISOString().split('T')[0]
    const record = dailyRecords.value.find(r => r.date === targetDate)
    if (record) {
      record.foodIntake += calories
    } else {
      dailyRecords.value.push({
        date: targetDate,
        foodIntake: calories,
        exerciseBurn: 0,
        targetCalories: targetCalories.value
      })
    }
  }

  const addExerciseBurn = (calories: number, date?: string) => {
    const targetDate = date || new Date().toISOString().split('T')[0]
    const record = dailyRecords.value.find(r => r.date === targetDate)
    if (record) {
      record.exerciseBurn += calories
    } else {
      dailyRecords.value.push({
        date: targetDate,
        foodIntake: 0,
        exerciseBurn: calories,
        targetCalories: targetCalories.value
      })
    }
  }

  const updateProfile = (newProfile: Partial<UserProfile>) => {
    profile.value = { ...profile.value, ...newProfile }
  }

  return {
    profile,
    dailyRecords,
    bmr,
    bmi,
    tdee,
    targetCalories,
    recommendedProtein,
    recommendedFat,
    recommendedCarbs,
    todayRecord,
    calorieDeficit,
    getWeeklyDeficit,
    initFromStorage,
    addFoodIntake,
    addExerciseBurn,
    updateProfile
  }
})