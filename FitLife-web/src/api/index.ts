/**
 * FitLife API 服务层
 * 封装所有后端接口调用
 */

import { http } from '../utils/http'

/**
 * 认证模块
 */
export const authApi = {
  /** 用户注册 */
  register(data: { username: string; password: string }) {
    return http.post('/auth/register', data)
  },

  /** 用户登录 */
  login(data: { username: string; password: string }) {
    return http.post('/auth/login', data)
  }
}

/**
 * 用户档案模块
 */
export const profileApi = {
  /** 获取用户档案 */
  getProfile() {
    return http.get('/data/profile')
  },

  /** 更新用户档案 */
  updateProfile(data: {
    name: string
    gender: string
    age: number
    height: number
    weight: number
    bodyFat: number
    goal: string
    activityLevel: string
    experience?: string
    weeklyDays?: number
    sessionMinutes?: number
    equipments?: string
    injuries?: string
  }) {
    return http.put('/data/profile', data)
  }
}

/**
 * 每日记录模块
 */
export const dailyRecordApi = {
  /** 获取今日记录 */
  getTodayRecord() {
    return http.get('/data/daily-record/today')
  },

  /** 获取每日记录列表 */
  getDailyRecords(params?: { startDate?: string; endDate?: string }) {
    return http.get('/data/daily-records', params)
  }
}

/**
 * 食物摄入模块
 */
export const foodApi = {
  /** 添加食物摄入 */
  addFoodIntake(data: { calories: number; foodId?: string; amount?: number }) {
    return http.post('/data/food-intake', data)
  }
}

/**
 * 运动记录模块
 */
export const exerciseApi = {
  /** 添加运动消耗 */
  addExerciseBurn(data: { calories: number; exerciseId: string; duration: number }) {
    return http.post('/data/exercise-burn', data)
  },

  /** 获取运动记录列表 */
  getWorkoutRecords(params?: { startDate?: string; endDate?: string }) {
    return http.get('/data/workout-records', params)
  }
}

/**
 * 今日训练卡与打卡（P4：执行闭环）
 */
export const todayPlanApi = {
  /** 今日训练卡：动作、组次、目标心率区间、打卡状态 */
  get() {
    return http.get('/data/today-plan')
  }
}

export const checkInApi = {
  /** 打卡 / 跳过 / 部分完成（幂等，可带 Idempotency-Key） */
  submit(data: {
    planDetailId?: number
    exerciseId?: string
    exerciseName?: string
    duration?: number
    calories?: number
    status?: 'DONE' | 'PARTIAL' | 'SKIPPED'
    skippedReasons?: string
    rpe?: number
    pain?: boolean
    date?: string
    planId?: number
  }) {
    return http.post('/data/check-in', data)
  },

  /** 撤销某条打卡 */
  undo(recordId: number) {
    return http.delete(`/data/check-in/${recordId}`)
  }
}

/**
 * 统计与进阶建议（P4：服务端唯一口径）
 */
export const statsApi = {
  /** 区间统计：依从率、训练量、热量收支、RPE 偏差、风险提示分级 */
  summary(params?: { days?: number; startDate?: string; endDate?: string }) {
    return http.get('/data/stats/summary', params)
  },

  /** 反馈与调整决策：HOLD / DELOAD / PROGRESS / SWAP / STOP */
  advice(params?: { days?: number }) {
    return http.get('/data/stats/advice', params)
  },

  /** 按建议生成下一版计划（≤10% 增幅，parent 指向上版） */
  advance(params?: { days?: number }) {
    return http.post('/data/stats/advance', params || {}, { timeout: 120000 })
  }
}

/**
 * 动作库模块（知识库查询）
 */
export const exerciseLibApi = {
  /** 动作列表，支持按类别/肌群/器械/难度筛选 */
  list(params?: { category?: string; muscleGroup?: string; equipment?: string; maxDifficulty?: number }) {
    return http.get('/exercise/list', params)
  },

  /** 动作库统计 */
  stats() {
    return http.get('/exercise/stats')
  },

  /** 单个动作详情 */
  detail(id: number) {
    return http.get(`/exercise/${id}`)
  }
}

/**
 * 训练计划模块
 */
export const planApi = {
  /** 获取当前生效计划 */
  current() {
    return http.get('/plan/current')
  },

  /** 历史计划列表 */
  list() {
    return http.get('/plan/list')
  },

  /** 计划详情 */
  detail(id: number) {
    return http.get(`/plan/${id}`)
  },

  /** 生成训练计划 */
  generate(data?: {
    goal?: string
    experience?: string
    weeklyDays?: number
    sessionMinutes?: number
    equipments?: string
    injuries?: string
    force?: boolean
    model?: string
  }) {
    return http.post('/plan/generate', data || {}, { timeout: 120000 })
  },

  /** 版本回滚：把历史版本设为生效（P2） */
  activate(id: number) {
    return http.post(`/plan/${id}/activate`)
  },

  /** 对话式局部修订（P3）：自然语言改计划，返回结构化 Diff + 新版本 */
  revise(data: { message: string; sessionKey?: string; model?: string; allowAction?: boolean }) {
    return http.post('/plan/revise', data, { timeout: 60000 })
  },

  /** 删除计划 */
  remove(id: number) {
    return http.delete(`/plan/${id}`)
  },

  /** 对话式生成：自然语言 → 落库的一周训练计划 / 运动搭配 */
  chat(message: string, force = true) {
    return http.post('/plan/chat', { message, force }, { timeout: 120000 })
  },

  /** 智能运动搭配：从动作库挑一组带组次、要领、热量的动作 */
  match(data?: {
    goal?: string
    minutes?: number
    experience?: string
    equipments?: string
    injuries?: string
    muscleGroup?: string
    weight?: number
  }) {
    return http.post('/plan/match', data || {}, { timeout: 120000 })
  }
}

/**
 * 自定义食物模块
 */
export const customFoodApi = {
  /** 获取自定义食物列表 */
  getCustomFoods() {
    return http.get('/data/custom-foods')
  },

  /** 添加自定义食物 */
  addCustomFood(data: {
    name: string
    calories: number
    protein: number
    carbs: number
    fat: number
    servingSize: number
    unit: string
    category: string
  }) {
    return http.post('/data/custom-foods', data)
  },

  /** 删除自定义食物 */
  deleteCustomFood(id: string) {
    return http.delete(`/data/custom-foods/${id}`)
  }
}

/**
 * 数据同步模块
 */
export const syncApi = {
  /** 同步所有数据 */
  syncAllData() {
    return http.get('/data/sync')
  }
}

/**
 * 健康检查模块
 */
export const healthApi = {
  /** 检查服务状态 */
  checkHealth() {
    return http.get('/health')
  }
}

/**
 * 导出所有 API 模块
 */
export const api = {
  auth: authApi,
  profile: profileApi,
  dailyRecord: dailyRecordApi,
  food: foodApi,
  exercise: exerciseApi,
  exerciseLib: exerciseLibApi,
  plan: planApi,
  customFood: customFoodApi,
  sync: syncApi,
  health: healthApi
}

export default api