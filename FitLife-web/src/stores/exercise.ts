import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'

export interface ExerciseItem {
  id: string
  name: string
  caloriesPerHour: number
  category: 'cardio' | 'strength' | 'flexibility' | 'sports'
  difficulty: 'easy' | 'medium' | 'hard'
  description: string
}

export interface WorkoutRecord {
  date: string
  exerciseId: string
  duration: number
  caloriesBurned: number
}

const STORAGE_KEY_WORKOUTS = 'fitlife_workoutRecords'

export const useExerciseStore = defineStore('exercise', () => {
  const exerciseDatabase: ExerciseItem[] = [
    { id: '1', name: '跑步', caloriesPerHour: 600, category: 'cardio', difficulty: 'medium', description: '户外或跑步机跑步' },
    { id: '2', name: '快走', caloriesPerHour: 350, category: 'cardio', difficulty: 'easy', description: '每小时5-6公里速度行走' },
    { id: '3', name: '游泳', caloriesPerHour: 500, category: 'cardio', difficulty: 'medium', description: '自由泳或蛙泳' },
    { id: '4', name: '骑自行车', caloriesPerHour: 450, category: 'cardio', difficulty: 'medium', description: '户外骑行或动感单车' },
    { id: '5', name: '跳绳', caloriesPerHour: 700, category: 'cardio', difficulty: 'medium', description: '中等速度跳绳' },
    { id: '6', name: 'HIIT训练', caloriesPerHour: 650, category: 'cardio', difficulty: 'hard', description: '高强度间歇训练' },
    { id: '7', name: '瑜伽', caloriesPerHour: 200, category: 'flexibility', difficulty: 'easy', description: '柔韧性和放松训练' },
    { id: '8', name: '普拉提', caloriesPerHour: 350, category: 'flexibility', difficulty: 'medium', description: '核心力量和柔韧训练' },
    { id: '9', name: '力量训练', caloriesPerHour: 400, category: 'strength', difficulty: 'medium', description: '器械或自由重量训练' },
    { id: '10', name: '引体向上', caloriesPerHour: 450, category: 'strength', difficulty: 'hard', description: '背部力量训练' },
    { id: '11', name: '深蹲', caloriesPerHour: 350, category: 'strength', difficulty: 'medium', description: '下肢力量训练' },
    { id: '12', name: '硬拉', caloriesPerHour: 500, category: 'strength', difficulty: 'hard', description: '全身复合力量训练' },
    { id: '13', name: '篮球', caloriesPerHour: 480, category: 'sports', difficulty: 'medium', description: '篮球运动或比赛' },
    { id: '14', name: '足球', caloriesPerHour: 520, category: 'sports', difficulty: 'medium', description: '足球运动或比赛' },
    { id: '15', name: '羽毛球', caloriesPerHour: 400, category: 'sports', difficulty: 'medium', description: '羽毛球运动' },
    { id: '16', name: '乒乓球', caloriesPerHour: 280, category: 'sports', difficulty: 'easy', description: '乒乓球运动' },
    { id: '17', name: '爬山', caloriesPerHour: 550, category: 'cardio', difficulty: 'hard', description: '户外爬山运动' },
    { id: '18', name: '跳舞', caloriesPerHour: 400, category: 'cardio', difficulty: 'medium', description: '舞蹈或有氧舞蹈' },
    { id: '19', name: '拳击', caloriesPerHour: 600, category: 'cardio', difficulty: 'hard', description: '拳击或搏击训练' },
    { id: '20', name: '椭圆机', caloriesPerHour: 420, category: 'cardio', difficulty: 'easy', description: '健身房椭圆机训练' }
  ]

  const workoutRecords = ref<WorkoutRecord[]>([])

  // 监听数据变化，自动保存到 localStorage
  watch(workoutRecords, (newRecords) => {
    try {
      localStorage.setItem(STORAGE_KEY_WORKOUTS, JSON.stringify(newRecords))
    } catch (error) {
      console.error('保存运动记录失败:', error)
    }
  }, { deep: true })

  // 从 localStorage 加载运动记录
  const loadFromStorage = (): WorkoutRecord[] | null => {
    try {
      const data = localStorage.getItem(STORAGE_KEY_WORKOUTS)
      if (data) {
        return JSON.parse(data)
      }
    } catch (error) {
      console.error('加载运动记录失败:', error)
    }
    return null
  }

  // 初始化数据 - 从 localStorage 恢复
  const initFromStorage = () => {
    const savedRecords = loadFromStorage()
    if (savedRecords) {
      workoutRecords.value = savedRecords
    }
  }

  const todayWorkouts = computed(() => {
    const today = new Date().toISOString().split('T')[0]
    return workoutRecords.value.filter(r => r.date === today)
  })

  const todayCaloriesBurned = computed(() => {
    return todayWorkouts.value.reduce((sum, r) => sum + r.caloriesBurned, 0)
  })

  const getExerciseById = (id: string) => {
    return exerciseDatabase.find(e => e.id === id)
  }

  const calculateCalories = (exerciseId: string, durationMinutes: number) => {
    const exercise = getExerciseById(exerciseId)
    if (!exercise) return 0
    return Math.round((exercise.caloriesPerHour / 60) * durationMinutes)
  }

  const addWorkout = (exerciseId: string, durationMinutes: number, date?: string) => {
    const calories = calculateCalories(exerciseId, durationMinutes)
    const targetDate = date || new Date().toISOString().split('T')[0]
    workoutRecords.value.push({
      date: targetDate,
      exerciseId,
      duration: durationMinutes,
      caloriesBurned: calories
    })
    return calories
  }

  const getWeeklyStats = () => {
    const result: { date: string; calories: number }[] = []
    for (let i = 6; i >= 0; i--) {
      const date = new Date()
      date.setDate(date.getDate() - i)
      const dateStr = date.toISOString().split('T')[0]
      const dayCalories = workoutRecords.value
        .filter(r => r.date === dateStr)
        .reduce((sum, r) => sum + r.caloriesBurned, 0)
      result.push({ date: dateStr, calories: dayCalories })
    }
    return result
  }

  return {
    exerciseDatabase,
    workoutRecords,
    todayWorkouts,
    todayCaloriesBurned,
    initFromStorage,
    getExerciseById,
    calculateCalories,
    addWorkout,
    getWeeklyStats
  }
})