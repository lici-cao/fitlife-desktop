import { http, API_BASE_URL } from '../utils/http'

/** 模型选择器里的一个选项 */
export interface AiModelOption {
  id: string
  name: string
  providerId: string
  providerName: string
  tag?: string
  free?: boolean
  available?: boolean
  isDefault?: boolean
  note?: string
}

/** 流式对话事件 */
export interface ChatStreamEvent {
  type: 'meta' | 'delta' | 'plan' | 'match' | 'exercises' | 'done' | 'error' | 'fallback'
  text?: string
  message?: string
  reason?: string
  model?: string
  modelName?: string
  provider?: string
  action?: string
  plan?: any
  match?: any
  items?: any[]
}

export interface ChatPayload {
  message: string
  model?: string
  sessionId?: string
  history?: Array<{ role: string; content: string }>
  context?: Record<string, any>
  allowAction?: boolean
}

/**
 * AI健身搭子服务
 * 连接国内AI服务，提供智能健身建议
 */
export const aiApi = {
  /**
   * 预测体重变化
   * @param data 用户数据
   * @returns 体重预测结果
   */
  predictWeight(data: {
    currentWeight: number
    targetWeight: number
    calorieDeficit: number
    activityLevel: string
    days: number
  }) {
    return http.post('/ai/predict-weight', data)
  },

  /**
   * 获取饮食建议
   * @param data 用户饮食和目标数据
   * @returns 饮食建议
   */
  getDietSuggestion(data: {
    foodIntake: number
    targetCalories: number
    goal: string
    protein: number
    carbs: number
    fat: number
  }) {
    return http.post('/ai/diet-suggestion', data)
  },

  /**
   * 获取运动建议
   * @param data 用户运动数据
   * @returns 运动建议
   */
  getExerciseSuggestion(data: {
    exerciseBurn: number
    targetCalories: number
    activityLevel: string
    goal: string
  }) {
    return http.post('/ai/exercise-suggestion', data)
  },

  /**
   * 智能聊天
   * @param message 用户消息
   * @param context 上下文对象
   * @returns AI回复
   */
  chat(message: string, context?: Record<string, any>) {
    return http.post('/ai/chat', { message, context })
  },

  /**
   * 获取今日总结
   * @param data 用户当日数据
   * @returns 今日总结
   */
  getDailySummary(data: {
    foodIntake: number
    exerciseBurn: number
    targetCalories: number
    weight?: number
    goal: string
  }) {
    return http.post('/ai/daily-summary', data)
  }
}

/**
 * 工作台式对话接口
 * - models：可用模型清单（DeepSeek / 豆包 / 智谱）
 * - stream：SSE 流式对话（fetch 读取，支持中止）
 * - send：非流式对话（降级）
 */
export const chatApi = {
  models() {
    return http.get('/chat/models')
  },

  send(payload: ChatPayload) {
    return http.post('/chat', payload)
  },

  async stream(
    payload: ChatPayload,
    onEvent: (event: ChatStreamEvent) => void,
    signal?: AbortSignal
  ): Promise<void> {
    const token = localStorage.getItem('fitlife_token')
    const headers: Record<string, string> = { 'Content-Type': 'application/json' }
    if (token) headers.Authorization = `Bearer ${token}`

    const res = await fetch(`${API_BASE_URL}/chat/stream`, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
      signal
    })

    if (!res.ok || !res.body) {
      throw new Error(`对话接口返回 ${res.status}`)
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    // 按 SSE 的空行切分事件
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const chunks = buffer.split('\n\n')
      buffer = chunks.pop() || ''
      for (const chunk of chunks) {
        const dataLine = chunk.split('\n').find(line => line.startsWith('data:'))
        if (!dataLine) continue
        const raw = dataLine.slice(5).trim()
        if (!raw) continue
        try {
          onEvent(JSON.parse(raw) as ChatStreamEvent)
        } catch {
          // 忽略无法解析的分片
        }
      }
    }
  }
}