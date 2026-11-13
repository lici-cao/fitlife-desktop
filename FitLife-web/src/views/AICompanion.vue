<script setup lang="ts">
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'
import { useAppStore } from '../stores/app'
import { aiApi, chatApi } from '../api/ai'
import type { AiModelOption, ChatStreamEvent } from '../api/ai'
import { planApi } from '../api'
import { renderMarkdown } from '../utils/markdown'

const userStore = useUserStore()
const appStore = useAppStore()
const router = useRouter()

// ============================================================
// 一、会话与消息模型
// ============================================================

interface ChatMsg {
  id: string
  role: 'user' | 'assistant'
  content: string
  ts: number
  streaming?: boolean
  modelLabel?: string
  /** 走了规则兜底 */
  fallback?: boolean
  /** 演示环境：本地模板按档案计算生成，不是大模型输出 */
  demo?: boolean
  /** 出错提示 */
  error?: string
  plan?: any
  match?: any
  exercises?: any[]
}

interface ChatSession {
  id: string
  title: string
  createdAt: number
  updatedAt: number
  messages: ChatMsg[]
}

const STORAGE_KEY = 'fitlife_ai_sessions_v2'
const MODEL_KEY = 'fitlife_ai_model'

const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6)

const sessions = ref<ChatSession[]>([])
const activeId = ref('')
const activeSession = computed(() => sessions.value.find(s => s.id === activeId.value) || null)
const messages = computed<ChatMsg[]>(() => activeSession.value?.messages || [])

const input = ref('')
const sending = ref(false)
const scrollRef = ref<HTMLElement | null>(null)
const textareaRef = ref<HTMLTextAreaElement | null>(null)

let controller: AbortController | null = null

const loadSessions = () => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      if (Array.isArray(parsed)) sessions.value = parsed
    }
  } catch {
    sessions.value = []
  }
  if (!sessions.value.length) {
    createSession()
  } else {
    activeId.value = sessions.value[0].id
  }
}

const persist = () => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions.value.slice(0, 40)))
  } catch {
    // 超出配额时忽略
  }
}

const createSession = () => {
  // 已有空会话就直接复用，避免出现一堆空标题
  const empty = sessions.value.find(s => s.messages.length === 0)
  if (empty) {
    activeId.value = empty.id
    nextTick(() => textareaRef.value?.focus())
    return
  }
  const session: ChatSession = {
    id: uid(),
    title: '新的对话',
    createdAt: Date.now(),
    updatedAt: Date.now(),
    messages: []
  }
  sessions.value.unshift(session)
  activeId.value = session.id
  persist()
  nextTick(() => textareaRef.value?.focus())
}

const openSession = (id: string) => {
  activeId.value = id
  nextTick(scrollToBottom)
}

const removeSession = (id: string) => {
  const index = sessions.value.findIndex(s => s.id === id)
  if (index < 0) return
  sessions.value.splice(index, 1)
  if (activeId.value === id) {
    activeId.value = sessions.value[0]?.id || ''
    if (!sessions.value.length) createSession()
  }
  persist()
}

const groupedSessions = computed(() => {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const start = today.getTime()
  const list = [...sessions.value].sort((a, b) => b.updatedAt - a.updatedAt)
  return {
    today: list.filter(s => s.updatedAt >= start),
    earlier: list.filter(s => s.updatedAt < start)
  }
})

// ============================================================
// 二、模型选择
// ============================================================

/** 后端不可达时的兜底模型清单（保证界面可用） */
const FALLBACK_MODELS: AiModelOption[] = [
  {
    id: 'deepseek-chat', name: 'DeepSeek V3.2', providerId: 'deepseek', providerName: 'DeepSeek',
    tag: '免费额度', free: true, available: true, isDefault: true, note: '通用对话与计划编排，速度快'
  },
  {
    id: 'deepseek-reasoner', name: 'DeepSeek R1', providerId: 'deepseek', providerName: 'DeepSeek',
    tag: '推理', free: true, available: true, note: '复杂问题逐步推理'
  },
  {
    id: 'doubao-seed-1-6-flash', name: '豆包 Seed 1.6 Flash', providerId: 'doubao', providerName: '豆包',
    tag: '免费额度', free: true, available: true, note: '极速低价，日常问答够用'
  },
  {
    id: 'doubao-seed-1-6', name: '豆包 Seed 1.6', providerId: 'doubao', providerName: '豆包',
    tag: '综合', free: true, available: true, note: '综合能力更强'
  },
  {
    id: 'glm-4-flash', name: 'GLM-4-Flash', providerId: 'glm', providerName: '智谱 GLM',
    tag: '免费', free: true, available: true, note: '免费兜底模型'
  }
]

const models = ref<AiModelOption[]>([])
const currentModelId = ref(localStorage.getItem(MODEL_KEY) || 'deepseek-chat')
const modelPanelOpen = ref(false)
const modelsLoaded = ref(false)

const currentModel = computed<AiModelOption | null>(
  () => models.value.find(m => m.id === currentModelId.value) || models.value[0] || null
)

const providerGroups = computed(() => {
  const groups: Array<{ id: string; name: string; items: AiModelOption[] }> = []
  models.value.forEach(m => {
    let group = groups.find(g => g.id === m.providerId)
    if (!group) {
      group = { id: m.providerId, name: m.providerName, items: [] }
      groups.push(group)
    }
    group.items.push(m)
  })
  return groups
})

const anyAvailable = computed(() => models.value.some(m => m.available))

const selectModel = (m: AiModelOption) => {
  if (!m.available) {
    ElMessage.warning(m.note || '该模型尚未配置 API Key')
    return
  }
  currentModelId.value = m.id
  localStorage.setItem(MODEL_KEY, m.id)
  modelPanelOpen.value = false
  ElMessage.success(`已切换到 ${m.name}`)
}

const loadModels = async () => {
  try {
    const res: any = await chatApi.models()
    const data = res.data?.data
    if (data?.models?.length) {
      models.value = data.models
      if (data.defaultModel && !models.value.some(m => m.id === currentModelId.value)) {
        currentModelId.value = data.defaultModel
      }
      // 选中的模型没 Key 时，自动落到第一个可用模型
      const picked = models.value.find(m => m.id === currentModelId.value)
      if (picked && !picked.available) {
        const first = models.value.find(m => m.available)
        if (first) currentModelId.value = first.id
      }
    } else {
      models.value = FALLBACK_MODELS
    }
  } catch {
    models.value = FALLBACK_MODELS
  } finally {
    modelsLoaded.value = true
  }
}

// ============================================================
// 三、发送与流式接收
// ============================================================

const demoMode =
  (import.meta.env.DEV || import.meta.env.VITE_DEMO === '1') &&
  localStorage.getItem('fitlife_demo') === '1'

const scrollToBottom = () => {
  nextTick(() => {
    const el = scrollRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

const buildContext = () => {
  const profile = userStore.profile
  const today = todayData.value
  return {
    userProfile: {
      name: profile.name,
      gender: profile.gender,
      age: profile.age,
      weight: profile.weight,
      height: profile.height,
      bodyFat: profile.bodyFat,
      goal: profile.goal,
      activityLevel: profile.activityLevel,
      experience: profile.experience,
      equipments: profile.equipments,
      injuries: profile.injuries
    },
    todayData: {
      foodIntake: today.foodIntake,
      exerciseBurn: today.exerciseBurn,
      targetCalories: today.targetCalories
    },
    targetCalories: userStore.targetCalories,
    bmr: userStore.bmr,
    tdee: userStore.tdee
  }
}

const send = async (preset?: string) => {
  const text = (preset ?? input.value).trim()
  if (!text || sending.value) return

  if (!activeSession.value) createSession()
  const session = activeSession.value
  if (!session) return

  const userMsg: ChatMsg = { id: uid(), role: 'user', content: text, ts: Date.now() }
  session.messages.push(userMsg)
  session.updatedAt = Date.now()
  if (session.title === '新的对话') {
    session.title = text.length > 18 ? `${text.slice(0, 18)}…` : text
  }
  input.value = ''
  autoGrow()

  // 历史（不含刚插入的这条）
  const history = session.messages
    .filter(m => m.content && m.id !== userMsg.id)
    .slice(-10)
    .map(m => ({ role: m.role, content: m.content }))

  session.messages.push({ id: uid(), role: 'assistant', content: '', ts: Date.now(), streaming: true })
  const reply = session.messages[session.messages.length - 1]

  sending.value = true
  scrollToBottom()
  controller = new AbortController()

  try {
    if (demoMode) {
      reply.demo = true
      await demoStream(reply, text)
    } else {
      await chatApi.stream(
        {
          message: text,
          model: currentModelId.value,
          sessionId: session.id,
          history,
          context: buildContext(),
          allowAction: true
        },
        ev => handleEvent(ev, reply),
        controller.signal
      )
    }
  } catch (e: any) {
    if (e?.name === 'AbortError') {
      if (!reply.content) reply.content = '_已停止生成_'
    } else {
      reply.fallback = true
      reply.error = e?.message || '请求失败'
      if (!reply.content) reply.content = localAnswer(text, reply.error)
    }
  } finally {
    reply.streaming = false
    sending.value = false
    controller = null
    session.updatedAt = Date.now()
    persist()
    scrollToBottom()
  }
}

const handleEvent = (ev: ChatStreamEvent, reply: ChatMsg) => {
  switch (ev.type) {
    case 'meta':
      reply.modelLabel = `${ev.provider || ''} · ${ev.modelName || ev.model || ''}`.replace(/^ · /, '')
      break
    case 'delta':
      reply.content += ev.text || ''
      scrollToBottom()
      break
    case 'plan':
      reply.plan = ev.plan
      ElMessage.success('训练计划已生成并保存到「训练计划」页')
      break
    case 'match':
      reply.match = ev.match
      break
    case 'exercises':
      reply.exercises = ev.items || []
      break
    case 'fallback':
      reply.fallback = true
      if (!reply.content) reply.content = ev.text || ''
      break
    case 'error':
      reply.error = ev.message
      break
    default:
      break
  }
}

const stopGenerating = () => {
  controller?.abort()
}

const regenerate = async () => {
  const session = activeSession.value
  if (!session || sending.value) return
  const lastUser = [...session.messages].reverse().find(m => m.role === 'user')
  if (!lastUser) return
  while (session.messages.length && session.messages[session.messages.length - 1].role === 'assistant') {
    session.messages.pop()
  }
  const index = session.messages.findIndex(m => m.id === lastUser.id)
  if (index >= 0) session.messages.splice(index, 1)
  await send(lastUser.content)
}

const copyMessage = async (msg: ChatMsg) => {
  try {
    await navigator.clipboard.writeText(msg.content)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('浏览器不支持自动复制')
  }
}

/** 演示模式下用本地文案模拟逐字输出 */
const demoStream = async (reply: ChatMsg, text: string) => {
  const full = demoAnswer(text)
  const step = 3
  for (let i = 0; i < full.length; i += step) {
    if (!sending.value) break
    reply.content = full.slice(0, i + step)
    scrollToBottom()
    // eslint-disable-next-line no-await-in-loop
    await new Promise(r => setTimeout(r, 18))
  }
  reply.content = full
}

/** 演示环境用档案里的真实数据做本地计算，避免给出「答非所问」的通用话术 */
const demoMetrics = () => {
  const p: any = userStore.profile || {}
  const weight = Number(p.weight) || 60
  const height = Number(p.height) || 165
  const age = Number(p.age) || 25
  const male = p.gender === 'male'
  const bmi = weight / Math.pow(height / 100, 2)
  const bmr = Math.round(10 * weight + 6.25 * height - 5 * age + (male ? 5 : -161))
  const factors: Record<string, number> = {
    sedentary: 1.2, light: 1.375, moderate: 1.55, active: 1.725, very_active: 1.9
  }
  const tdee = Math.round(bmr * (factors[p.activityLevel] || 1.4))
  const bfEstRaw = 1.2 * bmi + 0.23 * age - (male ? 10.8 : 0) - 5.4
  const bfMeasured = Number(p.bodyFat) > 0
  const bf = bfMeasured ? Number(p.bodyFat) : Math.round(bfEstRaw * 10) / 10
  const target = p.goal === 'muscle_gain' ? tdee + 300 : p.goal === 'maintain' ? tdee : tdee - 400
  return {
    weight, height, age, male,
    bmi: Math.round(bmi * 10) / 10,
    bmr, tdee, bf, bfMeasured,
    bfEst: Math.round(bfEstRaw * 10) / 10,
    target
  }
}

const demoAnswer = (text: string) => {
  const m = demoMetrics()
  const h2 = (m.height / 100).toFixed(2)
  const sexStr = m.male ? '男' : '女'

  // 1) 体脂率 / 体成分
  if (text.includes('体脂')) {
    const rows = m.male
      ? [
          ['必需脂肪', '2–5%'], ['运动员', '6–13%'], ['健康', '14–17%'], ['可接受', '18–24%'], ['肥胖', '> 25%']
        ]
      : [
          ['必需脂肪', '10–13%'], ['运动员', '14–20%'], ['健康', '21–24%'], ['可接受', '25–31%'], ['肥胖', '> 32%']
        ]
    return `按你的档案（${sexStr} / ${m.age} 岁 / ${m.height}cm / ${m.weight}kg），体脂率约 **${m.bf}%**`
      + `${m.bfMeasured ? '（档案里的实测值）' : '（公式估算，误差 ±3~5%）'}。\n\n`
      + `### 怎么算出来的\n\n`
      + `1. 先算 BMI：${m.weight} ÷ ${h2}² = **${m.bmi}**\n`
      + `2. 代入 Deurenberg 公式：\`体脂率 = 1.2 × BMI + 0.23 × 年龄 − 10.8 × 性别 − 5.4\`（男取 1，女取 0）\n`
      + `3. 代入你的数值：1.2 × ${m.bmi} + 0.23 × ${m.age} − ${m.male ? '10.8' : '0'} − 5.4 = **${m.bfEst}%**\n\n`
      + (m.bfMeasured
        ? `> 你档案里填的实测值是 ${m.bf}%，与公式估算的 ${m.bfEst}% 相差 ${Math.abs(m.bf - m.bfEst).toFixed(1)} 个百分点，属于正常误差范围。\n\n`
        : `> 公式法是「只知道身高体重年龄」时的估算，误差一般 ±3~5 个百分点。\n\n`)
      + `### ${sexStr}性体脂参考区间\n\n`
      + `| 等级 | 体脂率 |\n| --- | --- |\n`
      + rows.map(r => `| ${r[0]} | ${r[1]} |`).join('\n')
      + `\n\n### 想要更准的数字\n\n`
      + `- **皮褶钳**：误差 ±3%，便宜，需要人帮你测\n`
      + `- **体脂秤 / InBody**：±3~5%，受喝水和训练影响大\n`
      + `- **DEXA 双能 X 光**：±1~2%，金标准但要去医院\n\n`
      + `建议固定条件测（早晨空腹、训练前、同一台设备），**看趋势比看绝对值更有意义**。`
  }

  // 2) 热量 / 代谢
  if (/热量|卡路里|代谢|TDEE|BMR|吃多少/i.test(text)) {
    const proteinLow = Math.round(m.weight * 1.6)
    const proteinHigh = Math.round(m.weight * 2)
    const fat = Math.round(m.weight * 0.8)
    const carb = Math.round((m.target - proteinLow * 4 - fat * 9) / 4)
    return `按你的档案算，每日总消耗 **${m.tdee} kcal**，减脂期建议吃 **${m.target} kcal**。\n\n`
      + `### 三步算出来\n\n`
      + `1. 基础代谢 BMR（Mifflin-St Jeor）：10 × ${m.weight} + 6.25 × ${m.height} − 5 × ${m.age} ${m.male ? '+ 5' : '− 161'} = **${m.bmr} kcal**\n`
      + `2. 乘活动系数（中等活动量 ×1.55）：${m.bmr} × 1.55 ≈ **${m.tdee} kcal**\n`
      + `3. 减脂在 TDEE 上减 400：${m.tdee} − 400 = **${m.target} kcal/天**，对应每周约减 0.3–0.4 kg\n\n`
      + `### 三大营养素怎么分\n\n`
      + `| 营养素 | 建议量 | 说明 |\n| --- | --- | --- |\n`
      + `| 蛋白质 | ${proteinLow}–${proteinHigh} g | 1.6–2.0 g/kg，减脂期保住肌肉的关键 |\n`
      + `| 脂肪 | ${fat} g 左右 | 0.8 g/kg，别低于这个数，影响激素 |\n`
      + `| 碳水 | ${carb} g 左右 | 剩下的热量全给碳水，优先训练前后吃 |\n\n`
      + `> 缺口别超过 500 kcal，掉太快掉的是肌肉和水。`
  }

  // 3) 蛋白质
  if (text.includes('蛋白质') || text.includes('蛋白')) {
    const low = Math.round(m.weight * 1.6)
    const high = Math.round(m.weight * 2)
    return `你的蛋白质目标是 **每天 ${low}–${high} g**（按 ${m.weight}kg × 1.6–2.0 g）。\n\n`
      + `### 分摊到四餐\n\n`
      + `| 餐次 | 蛋白质量 | 举例 |\n| --- | --- | --- |\n`
      + `| 早餐 | ${Math.round(low / 4)} g | 2 个鸡蛋 + 250ml 牛奶 |\n`
      + `| 午餐 | ${Math.round(low / 4)} g | 100g 鸡胸 / 牛肉 |\n`
      + `| 加餐 | ${Math.round(low / 4)} g | 一勺乳清蛋白或 150g 希腊酸奶 |\n`
      + `| 晚餐 | ${Math.round(low / 4)} g | 120g 鱼虾 / 豆腐 |\n\n`
      + `> 单餐超过 40g 利用率会下降，宁可分四餐，别一顿猛补。`
  }

  // 4) 伤病
  if (text.includes('膝盖') || text.includes('伤') || text.includes('疼')) {
    return `膝盖不适时，优先避开深蹲跳、箭步蹲这类**屈膝负荷大**的动作。\n\n`
      + `### 可以做的替代动作\n\n`
      + `| 部位 | 替代动作 | 组次 |\n| --- | --- | --- |\n`
      + `| 腿 | 臀桥 | 3 组 × 15 |\n| 腿 | 直腿硬拉 | 3 组 × 12 |\n| 臀 | 侧卧髋外展 | 3 组 × 15 |\n\n`
      + `### 判断要不要停练\n\n`
      + `- 训练中**刺痛、打软腿、肿胀** → 立刻停，去骨科\n`
      + `- 只有酸胀、活动开就缓解 → 可以练，但把幅度收小、重量降 30%\n\n`
      + `> 我不是医生，持续两周以上不缓解请先做影像检查。`
  }

  // 5) 生成计划
  if (text.includes('计划')) {
    return `按你的档案（${m.weight}kg / ${m.bf}% 体脂 / ${sexStr}性）排了一版 **推-拉-腿 + 有氧** 的分化，每次 45 分钟。\n\n`
      + `### 一周安排\n\n`
      + `| 训练日 | 主项 | 组次 |\n| --- | --- | --- |\n`
      + `| 周一 推 | 哑铃卧推 | 4 × 10 |\n`
      + `| 周三 拉 | 单臂哑铃划船 | 4 × 12 |\n`
      + `| 周五 腿 | 高脚杯深蹲 | 4 × 12 |\n`
      + `| 周日 有氧 | 快走 / 慢跑 30 分钟 | 心率 120–140 |\n\n`
      + `### 为什么这样排\n\n`
      + `- 每周 3 次力量训练，间隔 ≥48 小时，正好卡在恢复窗口内\n`
      + `- 每次 5 个动作：1 个主项 + 3 个辅助 + 1 个核心，控制在 45 分钟内\n`
      + `- 组间休息 60–90 秒，每组留 1–2 次余力（RPE 7）\n\n`
      + `> 演示环境没有落库能力。正式运行时说这句话，系统会真的生成并保存到「训练计划」页。`
  }

  // 6) 兜底：也要给具体数字，不要空泛
  return `先按你的档案给一版默认答案（${sexStr} / ${m.age} 岁 / ${m.height}cm / ${m.weight}kg / 体脂 ${m.bf}%）：\n\n`
    + `1. **热量**：每日消耗约 ${m.tdee} kcal，减脂吃 ${m.target} kcal，蛋白质 ${Math.round(m.weight * 1.6)}g 以上\n`
    + `2. **训练**：每周 3 次力量 + 1–2 次有氧，单次 45 分钟，优先练大肌群\n`
    + `3. **进度**：每周固定同一时间称重，看 4 周趋势，周降幅 0.3–0.5 kg 比较稳\n\n`
    + `你想问的如果是更具体的东西（体脂率怎么算、某块肌肉怎么练、今天还能吃多少），直接把问题说得再具体一点，我给到公式和数值。`
}

const localAnswer = (_text: string, reason?: string) => {
  const tip = reason ? `\n\n> 接口返回：${reason}` : ''
  return `**AI 服务暂时不可用**，不过内置规则引擎仍然在线：\n\n`
    + `- 说「帮我生成一周减脂计划」→ 直接按档案生成并保存训练计划\n`
    + `- 说「今天练胸怎么搭配」→ 从动作库挑一组带组次的训练\n`
    + `- 到「动作库」页可按肌群 / 器械 / 难度筛选动作${tip}`
}

const quickPrompts = [
  { icon: '📅', title: '生成一周减脂计划', desc: '按档案编排，直接落库', prompt: '帮我生成一周减脂计划，每周3天，每次45分钟' },
  { icon: '🏋️', title: '今天练胸怎么搭配', desc: '从动作库挑一组动作', prompt: '今天练胸怎么搭配？我有45分钟，只有哑铃' },
  { icon: '🦵', title: '膝盖不好怎么练腿', desc: '避开高风险动作', prompt: '膝盖不好，推荐几个安全的腿部动作' },
  { icon: '🔥', title: '我的体脂率怎么算', desc: '按档案算，给公式和数值', prompt: '我的体脂率怎么算' }
]

// ============================================================
// 四、输入框
// ============================================================

const autoGrow = () => {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 180)}px`
}

const onKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  }
}

// ============================================================
// 五、工具箱（运动搭配 / 体重预测 / 智能建议）
// ============================================================

const toolsOpen = ref(false)
const toolTab = ref<'match' | 'predict' | 'suggestion'>('match')

const matchForm = ref({
  goal: '减脂',
  minutes: 45,
  experience: '初级',
  equipments: ['徒手'] as string[],
  injuries: [] as string[],
  muscleGroup: ''
})
const matchResult = ref<any>(null)
const isMatching = ref(false)
const goalOptions = ['减脂', '增肌', '塑形', '体能']
const experienceOptions = ['初级', '中级', '高级']
const equipmentOptions = ['徒手', '哑铃', '杠铃', '器械', '弹力带', '壶铃']
const injuryOptions = ['膝关节', '腰椎', '肩关节', '腕关节']
const muscleOptions = ['胸', '背', '肩', '手臂', '腿', '臀', '核心', '全身']

const doMatch = async () => {
  if (!matchForm.value.equipments.length) {
    ElMessage.warning('请至少选择一种可用器械')
    return
  }
  isMatching.value = true
  try {
    const res: any = await planApi.match({
      goal: matchForm.value.goal,
      minutes: matchForm.value.minutes,
      experience: matchForm.value.experience,
      equipments: matchForm.value.equipments.join(','),
      injuries: matchForm.value.injuries.join(','),
      muscleGroup: matchForm.value.muscleGroup || undefined
    })
    if (res.data?.code === 200) {
      matchResult.value = res.data.data
      ElMessage.success('已从动作库搭配一组训练动作')
    } else {
      ElMessage.error(res.data?.message || '搭配失败')
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '搭配失败，请确认后端与动作库已就绪')
  } finally {
    isMatching.value = false
  }
}

const matchToPlan = async () => {
  try {
    const res: any = await planApi.generate({
      goal: matchForm.value.goal,
      experience: matchForm.value.experience,
      weeklyDays: 3,
      sessionMinutes: matchForm.value.minutes,
      equipments: matchForm.value.equipments.join(','),
      injuries: matchForm.value.injuries.join(','),
      force: true
    })
    if (res.data?.code === 200) {
      ElMessage.success('一周训练计划已生成')
      toolsOpen.value = false
      router.push('/plan')
    } else {
      ElMessage.error(res.data?.message || '生成失败')
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '生成失败')
  }
}

const phaseType = (phase: string) => {
  const map: Record<string, string> = {
    热身: 'info', 主项: 'danger', 辅助: 'warning',
    核心: 'success', 有氧: 'primary', 拉伸: 'info'
  }
  return map[phase] || 'info'
}

const predictionDays = ref(7)
const predictionResult = ref<{ day: number; weight: number; change: number }[]>([])
const isPredicting = ref(false)

const predictWeight = async () => {
  if (isPredicting.value) return
  isPredicting.value = true
  try {
    const result = await aiApi.predictWeight({
      currentWeight: userStore.profile.weight,
      targetWeight: userStore.profile.weight - 5,
      calorieDeficit: userStore.targetCalories - userStore.tdee,
      activityLevel: userStore.profile.activityLevel,
      days: predictionDays.value
    })
    predictionResult.value = result.data?.data || simulatePrediction()
  } catch {
    predictionResult.value = simulatePrediction()
  } finally {
    isPredicting.value = false
  }
}

const simulatePrediction = () => {
  const result: { day: number; weight: number; change: number }[] = []
  const dailyChange = 5 / 30
  let current = userStore.profile.weight
  for (let i = 1; i <= predictionDays.value; i++) {
    current -= dailyChange
    result.push({
      day: i,
      weight: Math.round(current * 10) / 10,
      change: Math.round(-dailyChange * i * 10) / 10
    })
  }
  return result
}

const todayData = computed(() => {
  const record = userStore.dailyRecords.find(r => r.date === appStore.selectedDate)
  return record || {
    foodIntake: 0,
    exerciseBurn: 0,
    targetCalories: userStore.targetCalories
  }
})

const getTodaySuggestion = computed(() => {
  const intake = todayData.value.foodIntake
  const burn = todayData.value.exerciseBurn
  const target = userStore.targetCalories
  const deficit = target - intake + burn
  const list: Array<{ type: string; title: string; content: string }> = []

  if (intake < target * 0.8) {
    list.push({
      type: 'warning', title: '需要多吃一点',
      content: `今日摄入 ${intake} 千卡，低于目标 ${Math.round((target - intake) / target * 100)}%，可以适当增加营养摄入。`
    })
  } else if (intake > target * 1.1) {
    list.push({
      type: 'danger', title: '需要少吃一点',
      content: `今日摄入 ${intake} 千卡，超出目标 ${Math.round((intake - target) / target * 100)}%，明天可以适当控制。`
    })
  } else {
    list.push({
      type: 'success', title: '饮食控制良好',
      content: `今日摄入 ${intake} 千卡，符合目标要求，继续保持。`
    })
  }

  if (burn === 0) {
    list.push({ type: 'info', title: '今日尚未运动', content: '建议每天运动 30 分钟，可以选有氧或力量训练。' })
  } else if (burn < 200) {
    list.push({ type: 'info', title: '运动量较少', content: `今日运动消耗 ${burn} 千卡，可以适当增加强度或时间。` })
  } else {
    list.push({ type: 'success', title: '运动表现优秀', content: `今日运动消耗 ${burn} 千卡，继续保持。` })
  }

  if (deficit > 0) {
    list.push({
      type: 'success', title: '热量缺口正常',
      content: `今日热量缺口 ${Math.round(deficit)} 千卡，按这个节奏每周约减 ${Math.round(deficit * 7 / 7700 * 10) / 10}kg。`
    })
  } else {
    list.push({
      type: 'warning', title: '热量盈余',
      content: `今日热量盈余 ${Math.abs(Math.round(deficit))} 千卡，注意控制饮食或增加运动。`
    })
  }
  return list
})

// ============================================================
// 六、生命周期
// ============================================================

onMounted(() => {
  userStore.initFromStorage()
  loadSessions()
  loadModels()
  scrollToBottom()
})

watch(activeId, () => scrollToBottom())
</script>

<template>
  <div class="ai-workspace">
    <!-- ==================== 左：会话列表 ==================== -->
    <aside class="sessions">
      <div class="brand">
        <div class="brand-logo">F</div>
        <div class="brand-text">
          <strong>FitLife AI</strong>
          <span>训练搭子</span>
        </div>
      </div>

      <button class="new-chat" @click="createSession">
        <span class="plus">＋</span> 新建对话
      </button>

      <div class="session-scroll">
        <template v-if="groupedSessions.today.length">
          <div class="group-title">今天</div>
          <div
            v-for="s in groupedSessions.today"
            :key="s.id"
            :class="['session-item', { active: s.id === activeId }]"
            @click="openSession(s.id)"
          >
            <span class="s-title">{{ s.title }}</span>
            <span class="s-del" @click.stop="removeSession(s.id)">×</span>
          </div>
        </template>
        <template v-if="groupedSessions.earlier.length">
          <div class="group-title">更早</div>
          <div
            v-for="s in groupedSessions.earlier"
            :key="s.id"
            :class="['session-item', { active: s.id === activeId }]"
            @click="openSession(s.id)"
          >
            <span class="s-title">{{ s.title }}</span>
            <span class="s-del" @click.stop="removeSession(s.id)">×</span>
          </div>
        </template>
      </div>

      <div class="sessions-foot">
        <button class="foot-btn" @click="router.push('/exercise-library')">
          <span>📚</span> 动作库
        </button>
        <button class="foot-btn" @click="router.push('/plan')">
          <span>📅</span> 训练计划
        </button>
      </div>
    </aside>

    <!-- ==================== 右：对话区 ==================== -->
    <section class="conversation">
      <header class="conv-head">
        <div class="conv-title">
          {{ activeSession?.title || '新的对话' }}
          <span v-if="sending" class="live-dot">生成中</span>
        </div>
        <div class="conv-actions">
          <button class="ghost-btn" @click="toolsOpen = true">
            <span>🧰</span> 工具箱
          </button>
          <button class="ghost-btn" @click="createSession">
            <span>＋</span> 新对话
          </button>
        </div>
      </header>

      <div ref="scrollRef" class="conv-body">
        <!-- 欢迎页 -->
        <div v-if="!messages.length" class="welcome">
          <div class="welcome-logo">F</div>
          <h1>今天想练点什么？</h1>
          <p class="welcome-sub">
            我能按你的身体档案编排一周训练、从 100 个动作里挑一组搭配，
            也可以就今天的饮食和热量聊聊。
          </p>

          <div class="suggest-grid">
            <button
              v-for="q in quickPrompts"
              :key="q.title"
              class="suggest-card"
              @click="send(q.prompt)"
            >
              <span class="sc-icon">{{ q.icon }}</span>
              <span class="sc-title">{{ q.title }}</span>
              <span class="sc-desc">{{ q.desc }}</span>
            </button>
          </div>

          <div v-if="modelsLoaded && !anyAvailable" class="welcome-tip">
            当前没有配置任何模型 Key，对话会走内置规则引擎。填一个 Key 即可恢复自由问答。
          </div>
        </div>

        <!-- 消息列表 -->
        <div v-else class="msg-list">
          <div v-for="msg in messages" :key="msg.id" :class="['turn', msg.role]">
            <div class="turn-avatar">
              <span v-if="msg.role === 'user'">我</span>
              <span v-else>F</span>
            </div>

            <div class="turn-main">
              <div class="turn-meta">
                <span class="who">{{ msg.role === 'user' ? '我' : 'FitLife AI' }}</span>
                <span v-if="msg.modelLabel" class="model-tag">{{ msg.modelLabel }}</span>
                <span v-if="msg.demo" class="fallback-tag demo-tag">本地演示 · 未接入模型</span>
                <span v-if="msg.fallback" class="fallback-tag">规则兜底</span>
              </div>

              <div v-if="msg.role === 'user'" class="user-bubble">{{ msg.content }}</div>

              <div v-else class="assistant-body">
                <div v-if="msg.content" class="md" v-html="renderMarkdown(msg.content)"></div>
                <div v-else-if="msg.streaming" class="typing">
                  <i></i><i></i><i></i>
                </div>
                <span v-if="msg.streaming && msg.content" class="caret"></span>

                <!-- 对话生成的一周计划 -->
                <div v-if="msg.plan" class="card plan-card">
                  <div class="card-head">
                    <strong>📅 {{ msg.plan.title }}</strong>
                    <span :class="['pill', msg.plan.aiGenerated ? 'ok' : 'warn']">
                      {{ msg.plan.aiGenerated ? '大模型编排' : '规则模板' }}
                    </span>
                  </div>
                  <div class="card-meta">
                    {{ msg.plan.weeklyDays }} 天/周 · 每次 {{ msg.plan.sessionMinutes }} 分钟 ·
                    预估 {{ msg.plan.weeklyCalories }} kcal/周
                  </div>
                  <div class="card-summary">{{ msg.plan.summary }}</div>
                  <button class="card-btn" @click="router.push('/plan')">查看每天的动作安排 →</button>
                </div>

                <!-- 对话返回的运动搭配 -->
                <div v-if="msg.match" class="card">
                  <div class="card-head">
                    <strong>🏋️ {{ msg.match.focus }}</strong>
                    <span class="pill">{{ msg.match.items?.length || 0 }} 个动作</span>
                  </div>
                  <div class="card-meta">
                    约 {{ msg.match.totalMinutes }} 分钟 · 预估 {{ msg.match.estCalories }} kcal
                  </div>
                  <div v-for="(it, i) in msg.match.items" :key="i" class="mini-row">
                    <el-tag size="small" :type="phaseType(it.phase)">{{ it.phase }}</el-tag>
                    <span class="mini-name">{{ it.name }}</span>
                    <span class="mini-meta">{{ it.sets }} 组 × {{ it.reps }}</span>
                  </div>
                  <button class="card-btn" @click="matchResult = msg.match; toolTab = 'match'; toolsOpen = true">
                    查看动作要领 →
                  </button>
                </div>

                <!-- 引用到的动作库动作 -->
                <div v-if="msg.exercises?.length" class="card">
                  <div class="card-head"><strong>🔗 引用自系统动作库</strong></div>
                  <div v-for="ex in msg.exercises" :key="ex.id" class="mini-row">
                    <el-tag size="small" effect="plain">{{ ex.muscleGroup }}</el-tag>
                    <span class="mini-name">{{ ex.name }}</span>
                    <span class="mini-meta">{{ ex.defaultSets }} 组 × {{ ex.defaultReps }}</span>
                  </div>
                  <button class="card-btn" @click="router.push('/exercise-library')">打开动作库 →</button>
                </div>

                <div v-if="msg.error && !msg.content" class="error-line">{{ msg.error }}</div>
              </div>

              <div v-if="!msg.streaming" class="turn-actions">
                <button v-if="msg.content" @click="copyMessage(msg)">复制</button>
                <button v-if="msg.role === 'assistant' && msg === messages[messages.length - 1]" @click="regenerate">
                  重新生成
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="composer-wrap">
        <div v-if="demoMode" class="key-banner demo-banner">
          <span>ℹ️</span>
          <span>
            当前是<strong>本地演示环境</strong>，没有连接后端，回复由页面内置模板按你的档案计算生成，
            不是大模型输出。接上 Spring Boot 并配置 API Key 后才是真实模型回答。
          </span>
        </div>

        <div v-if="modelsLoaded && !anyAvailable && !demoMode" class="key-banner">
          <span>⚠️</span>
          <span>
            当前没有可用的模型 Key，对话会自动走规则引擎。在 <code>application.yml</code> 里填
            <code>DEEPSEEK_API_KEY</code> 或 <code>ARK_API_KEY</code> 即可开启自由问答。
          </span>
        </div>

        <div class="composer">
          <textarea
            ref="textareaRef"
            v-model="input"
            class="composer-input"
            rows="1"
            placeholder="描述你的目标、器械、时间或身体情况…（Enter 发送，Shift + Enter 换行）"
            @input="autoGrow"
            @keydown="onKeydown"
          ></textarea>

          <div class="composer-bar">
            <div class="model-picker">
              <button v-if="demoMode" class="model-chip" disabled>
                <span class="chip-dot off"></span>本地演示
              </button>
              <button v-else class="model-chip" @click="modelPanelOpen = !modelPanelOpen">
                <span class="chip-dot" :class="{ off: currentModel && !currentModel.available }"></span>
                {{ currentModel ? currentModel.name : '选择模型' }}
                <span class="caret-icon">▾</span>
              </button>

              <div v-if="modelPanelOpen" class="model-backdrop" @click="modelPanelOpen = false"></div>
              <div v-if="modelPanelOpen" class="model-panel">
                <div class="panel-title">选择模型</div>
                <div v-for="g in providerGroups" :key="g.id" class="model-group">
                  <div class="group-name">{{ g.name }}</div>
                  <button
                    v-for="m in g.items"
                    :key="m.id"
                    :class="['model-item', { selected: m.id === currentModelId, disabled: !m.available }]"
                    @click="selectModel(m)"
                  >
                    <div class="mi-main">
                      <span class="mi-name">{{ m.name }}</span>
                      <span v-if="m.tag" class="mi-tag">{{ m.tag }}</span>
                      <span v-if="!m.available" class="mi-off">未配置 Key</span>
                    </div>
                    <div class="mi-note">{{ m.note || '' }}</div>
                  </button>
                </div>
                <div class="panel-foot">
                  模型由后端 <code>ai.providers</code> 配置，可在 <code>application.yml</code> 中增删。
                </div>
              </div>
            </div>

            <span class="composer-hint">Enter 发送 · Shift + Enter 换行</span>

            <button v-if="sending" class="send-btn stop" @click="stopGenerating">停止</button>
            <button v-else class="send-btn" :disabled="!input.trim()" @click="send()">发送</button>
          </div>
        </div>
      </div>
    </section>

    <!-- ==================== 工具箱抽屉 ==================== -->
    <el-drawer v-model="toolsOpen" title="工具箱" size="720px" :with-header="true">
      <el-tabs v-model="toolTab">
        <el-tab-pane label="运动搭配" name="match">
          <div class="tool-grid">
            <div class="tool-form">
              <el-form label-position="top" size="default">
                <el-form-item label="健身目标">
                  <el-select v-model="matchForm.goal" style="width: 100%">
                    <el-option v-for="g in goalOptions" :key="g" :label="g" :value="g" />
                  </el-select>
                </el-form-item>
                <el-form-item :label="`本次时长（${matchForm.minutes} 分钟）`">
                  <el-slider v-model="matchForm.minutes" :min="20" :max="120" :step="5" />
                </el-form-item>
                <el-form-item label="运动经验">
                  <el-select v-model="matchForm.experience" style="width: 100%">
                    <el-option v-for="e in experienceOptions" :key="e" :label="e" :value="e" />
                  </el-select>
                </el-form-item>
                <el-form-item label="重点肌群">
                  <el-select v-model="matchForm.muscleGroup" placeholder="不限" clearable style="width: 100%">
                    <el-option v-for="m in muscleOptions" :key="m" :label="m" :value="m" />
                  </el-select>
                </el-form-item>
                <el-form-item label="可用器械">
                  <el-checkbox-group v-model="matchForm.equipments">
                    <el-checkbox v-for="eq in equipmentOptions" :key="eq" :value="eq" :label="eq" />
                  </el-checkbox-group>
                </el-form-item>
                <el-form-item label="伤病史 / 禁忌部位">
                  <el-checkbox-group v-model="matchForm.injuries">
                    <el-checkbox v-for="inj in injuryOptions" :key="inj" :value="inj" :label="inj" />
                  </el-checkbox-group>
                </el-form-item>
                <el-button type="primary" style="width: 100%" :loading="isMatching" @click="doMatch">
                  {{ isMatching ? '正在从动作库挑选…' : '智能搭配一组动作' }}
                </el-button>
              </el-form>
            </div>

            <div class="tool-result">
              <el-empty
                v-if="!matchResult"
                description="设置条件后点击下方按钮，动作全部来自系统动作库"
                :image-size="80"
              />
              <template v-else>
                <div class="card">
                  <div class="card-head">
                    <strong>{{ matchResult.focus }}</strong>
                    <span class="pill">{{ matchResult.aiGenerated ? 'AI 说明' : '规则说明' }}</span>
                  </div>
                  <div class="card-meta">
                    {{ matchResult.totalMinutes }} 分钟 · {{ matchResult.estCalories }} kcal ·
                    {{ matchResult.items?.length || 0 }} 个动作
                  </div>
                  <div class="card-summary">{{ matchResult.summary }}</div>
                  <button class="card-btn" @click="matchToPlan">扩展成一周计划 →</button>
                </div>
                <div class="tool-table">
                  <el-table :data="matchResult.items || []" size="small" stripe>
                    <el-table-column label="阶段" width="72" align="center">
                      <template #default="{ row }">
                        <el-tag size="small" :type="phaseType(row.phase)">{{ row.phase }}</el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column prop="name" label="动作" min-width="120" />
                    <el-table-column prop="muscleGroup" label="肌群" width="62" align="center" />
                    <el-table-column label="组次" width="94" align="center">
                      <template #default="{ row }">{{ row.sets }} × {{ row.reps }}</template>
                    </el-table-column>
                    <el-table-column label="消耗" width="80" align="center">
                      <template #default="{ row }">{{ row.estCalories }}</template>
                    </el-table-column>
                  </el-table>
                </div>
              </template>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="体重预测" name="predict">
          <p class="tool-desc">按当前热量缺口估算未来体重走向，仅作趋势参考。</p>
          <el-form-item :label="`预测天数：${predictionDays} 天`">
            <el-slider v-model="predictionDays" :min="1" :max="30" :marks="{ 7: '1周', 14: '2周', 30: '1月' }" />
          </el-form-item>
          <el-button type="primary" :loading="isPredicting" @click="predictWeight">开始预测</el-button>

          <div v-if="predictionResult.length" class="predict-list">
            <div v-for="item in predictionResult" :key="item.day" class="predict-row">
              <span>第 {{ item.day }} 天</span>
              <strong>{{ item.weight }} kg</strong>
              <span :class="item.change < 0 ? 'down' : 'up'">
                {{ item.change > 0 ? '+' : '' }}{{ item.change }} kg
              </span>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="今日建议" name="suggestion">
          <div class="today-grid">
            <div class="today-item">
              <strong>{{ todayData.foodIntake }}</strong>
              <span>今日摄入 kcal</span>
            </div>
            <div class="today-item">
              <strong>{{ todayData.exerciseBurn }}</strong>
              <span>今日消耗 kcal</span>
            </div>
            <div class="today-item">
              <strong>{{ userStore.targetCalories }}</strong>
              <span>每日目标 kcal</span>
            </div>
            <div class="today-item">
              <strong>{{ Math.round(userStore.calorieDeficit) }}</strong>
              <span>热量缺口 kcal</span>
            </div>
          </div>
          <div v-for="(s, i) in getTodaySuggestion" :key="i" :class="['suggest-row', s.type]">
            <strong>{{ s.title }}</strong>
            <p>{{ s.content }}</p>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>

<style scoped>
.ai-workspace {
  --ink: #1b1f24;
  --muted: #8a9099;
  --line: #e9ebef;
  --bg: #fbfbfc;
  --soft: #f5f6f8;
  --accent: #2f6bff;
  --accent-soft: #eef3ff;
  display: flex;
  height: 100%;
  min-height: 0;
  background: var(--bg);
  color: var(--ink);
  font-size: 14px;
}

/* ============ 左侧会话 ============ */
.sessions {
  width: 244px;
  flex: 0 0 244px;
  border-right: 1px solid var(--line);
  background: var(--soft);
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 16px 14px;
}

.brand-logo,
.welcome-logo {
  width: 28px;
  height: 28px;
  border-radius: 9px;
  background: linear-gradient(135deg, #2f6bff, #7a5cff);
  color: #fff;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 28px;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.brand-text strong {
  font-size: 14px;
  letter-spacing: 0.2px;
}

.brand-text span {
  font-size: 11px;
  color: var(--muted);
}

.new-chat {
  margin: 0 12px 10px;
  height: 36px;
  border-radius: 10px;
  border: 1px solid var(--line);
  background: #fff;
  color: var(--ink);
  font-size: 13px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  transition: all 0.15s;
}

.new-chat:hover {
  border-color: #c9d4ec;
  color: var(--accent);
  box-shadow: 0 2px 10px rgba(47, 107, 255, 0.08);
}

.session-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 4px 8px 8px;
}

.group-title {
  font-size: 11px;
  color: var(--muted);
  padding: 10px 8px 6px;
  letter-spacing: 0.4px;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  color: #414750;
  font-size: 13px;
  transition: background 0.12s;
}

.session-item:hover {
  background: #eceef2;
}

.session-item.active {
  background: #e4eaff;
  color: #1a3fd0;
  font-weight: 500;
}

.s-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.s-del {
  opacity: 0;
  color: var(--muted);
  font-size: 14px;
  line-height: 1;
  transition: opacity 0.12s;
}

.session-item:hover .s-del {
  opacity: 1;
}

.sessions-foot {
  border-top: 1px solid var(--line);
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.foot-btn {
  border: none;
  background: transparent;
  text-align: left;
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 13px;
  color: #4a5058;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
}

.foot-btn:hover {
  background: #eceef2;
}

/* ============ 右侧对话 ============ */
.conversation {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: #fff;
}

.conv-head {
  height: 54px;
  flex: 0 0 54px;
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}

.conv-title {
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 60%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.live-dot {
  font-size: 11px;
  font-weight: 400;
  color: var(--accent);
  background: var(--accent-soft);
  padding: 2px 8px;
  border-radius: 20px;
}

.conv-actions {
  display: flex;
  gap: 8px;
}

.ghost-btn {
  height: 30px;
  padding: 0 10px;
  border-radius: 8px;
  border: 1px solid var(--line);
  background: #fff;
  color: #4a5058;
  font-size: 12.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.ghost-btn:hover {
  border-color: #c9d4ec;
  color: var(--accent);
}

.conv-body {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0 24px;
  min-height: 0;
  scroll-behavior: smooth;
  display: flex;
  flex-direction: column;
}

/* 欢迎页 */
.welcome {
  max-width: 828px;
  width: 100%;
  margin: auto;
  padding: 32px 24px;
  text-align: center;
}

.welcome .welcome-logo {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  font-size: 20px;
  margin: 0 auto 18px;
}

.welcome h1 {
  font-size: 26px;
  letter-spacing: -0.4px;
  margin-bottom: 10px;
  font-weight: 650;
}

.welcome-sub {
  color: var(--muted);
  line-height: 1.7;
  font-size: 13.5px;
  max-width: 520px;
  margin: 0 auto;
}

.suggest-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 28px;
  text-align: left;
}

.suggest-card {
  display: grid;
  grid-template-columns: 28px 1fr;
  grid-template-rows: auto auto;
  column-gap: 10px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  transition: all 0.15s;
  text-align: left;
}

.suggest-card:hover {
  border-color: #c9d4ec;
  box-shadow: 0 6px 18px rgba(24, 39, 75, 0.06);
  transform: translateY(-1px);
}

.sc-icon {
  grid-row: span 2;
  font-size: 18px;
  line-height: 1.4;
}

.sc-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink);
}

.sc-desc {
  font-size: 12px;
  color: var(--muted);
  margin-top: 3px;
}

.welcome-tip {
  margin-top: 20px;
  font-size: 12px;
  color: #a2731a;
  background: #fff8e6;
  border: 1px solid #f5e2b3;
  border-radius: 10px;
  padding: 10px 14px;
  text-align: left;
}

/* 消息 */
.msg-list {
  max-width: 828px;
  width: 100%;
  margin: 0 auto;
  padding: 20px 24px 0;
}

.turn {
  display: flex;
  gap: 12px;
  padding: 10px 0 18px;
}

.turn-avatar {
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
}

.turn.user .turn-avatar {
  background: #e8ecf3;
  color: #5b6472;
}

.turn.assistant .turn-avatar {
  background: linear-gradient(135deg, #2f6bff, #7a5cff);
  color: #fff;
}

.turn-main {
  flex: 1;
  min-width: 0;
}

.turn-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.who {
  font-size: 12.5px;
  font-weight: 600;
  color: #3d434b;
}

.model-tag {
  font-size: 11px;
  color: var(--muted);
  background: var(--soft);
  border-radius: 6px;
  padding: 2px 6px;
}

.fallback-tag {
  font-size: 11px;
  color: #a2731a;
  background: #fff4dd;
  border-radius: 6px;
  padding: 2px 6px;
}

/* 演示环境提示：明确区分「本地模板」与「真实模型」 */
.demo-tag {
  color: #3a6ea5;
  background: #eaf3ff;
}

.user-bubble {
  display: inline-block;
  max-width: 100%;
  background: var(--accent-soft);
  color: #17317a;
  padding: 10px 14px;
  border-radius: 12px;
  border-top-left-radius: 4px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.turn.user {
  flex-direction: row-reverse;
}

.turn.user .turn-main {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

/* 用户消息不再重复显示头像与昵称，只保留气泡 */
.turn.user .turn-avatar,
.turn.user .turn-meta {
  display: none;
}

.turn.user .turn-meta,
.turn.user .turn-actions {
  align-self: flex-end;
}

.assistant-body {
  line-height: 1.78;
  color: #24292f;
  word-break: break-word;
}

.typing {
  display: inline-flex;
  gap: 4px;
  padding: 6px 0;
}

.typing i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #c2cad6;
  animation: blink 1.2s infinite;
}

.typing i:nth-child(2) {
  animation-delay: 0.2s;
}

.typing i:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes blink {
  0%, 60%, 100% { opacity: 0.35; transform: translateY(0); }
  30% { opacity: 1; transform: translateY(-2px); }
}

.caret {
  display: inline-block;
  width: 7px;
  height: 15px;
  background: var(--accent);
  vertical-align: -2px;
  margin-left: 2px;
  animation: caret 1s steps(2, start) infinite;
}

@keyframes caret {
  0%, 50% { opacity: 1; }
  50.01%, 100% { opacity: 0; }
}

.turn-actions {
  display: flex;
  gap: 12px;
  margin-top: 8px;
  opacity: 0;
  transition: opacity 0.15s;
}

.turn:hover .turn-actions {
  opacity: 1;
}

.turn-actions button {
  border: none;
  background: transparent;
  color: var(--muted);
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}

.turn-actions button:hover {
  color: var(--accent);
}

.error-line {
  margin-top: 8px;
  font-size: 12px;
  color: #c0392b;
}

/* Markdown 排版 */
.md :deep(h3),
.md :deep(h4),
.md :deep(h5) {
  margin: 14px 0 8px;
  font-size: 15px;
  font-weight: 650;
  color: #1b1f24;
}

.md :deep(p) {
  margin: 0 0 10px;
}

.md :deep(ul),
.md :deep(ol) {
  margin: 6px 0 12px;
  padding-left: 22px;
}

.md :deep(li) {
  margin: 4px 0;
}

.md :deep(ul) {
  list-style: disc;
}

.md :deep(ol) {
  list-style: decimal;
}

.md :deep(strong) {
  font-weight: 650;
  color: #111;
}

.md :deep(code) {
  background: #f2f4f7;
  border-radius: 5px;
  padding: 1px 5px;
  font-size: 12.5px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  color: #b4267a;
}

.md :deep(.md-code) {
  background: #f8f9fb;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
  overflow-x: auto;
  margin: 8px 0 12px;
}

.md :deep(.md-code code) {
  background: transparent;
  padding: 0;
  color: #2c3440;
  font-size: 12.5px;
  line-height: 1.7;
}

.md :deep(blockquote) {
  border-left: 3px solid #cfd9f5;
  background: #f7f9ff;
  margin: 8px 0 12px;
  padding: 8px 12px;
  color: #4a5468;
  border-radius: 0 8px 8px 0;
}

.md :deep(hr) {
  border: none;
  border-top: 1px solid var(--line);
  margin: 14px 0;
}

.md :deep(a) {
  color: var(--accent);
  text-decoration: none;
}

.md :deep(.md-table) {
  overflow-x: auto;
  margin: 10px 0 14px;
}

.md :deep(table) {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.md :deep(th),
.md :deep(td) {
  border: 1px solid var(--line);
  padding: 7px 10px;
  text-align: left;
}

.md :deep(th) {
  background: var(--soft);
  font-weight: 600;
}

/* 结果卡片 */
.card {
  margin-top: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 14px;
  background: #fcfdff;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 13.5px;
  margin-bottom: 6px;
}

.pill {
  font-size: 11px;
  color: #5a6b8c;
  background: #eef2fa;
  border-radius: 20px;
  padding: 2px 9px;
  white-space: nowrap;
}

.pill.ok {
  color: #1a7f4b;
  background: #e8f7ee;
}

.pill.warn {
  color: #a2731a;
  background: #fff4dd;
}

.card-meta {
  font-size: 12px;
  color: var(--muted);
  margin-bottom: 8px;
}

.card-summary {
  font-size: 13px;
  color: #4a5058;
  line-height: 1.7;
  margin-bottom: 10px;
}

.card-btn {
  border: none;
  background: transparent;
  color: var(--accent);
  font-size: 12.5px;
  cursor: pointer;
  padding: 0;
}

.mini-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 0;
  border-bottom: 1px dashed #eef0f4;
  font-size: 12.5px;
}

.mini-row:last-of-type {
  border-bottom: none;
}

.mini-name {
  font-weight: 500;
  color: #2d333b;
}

.mini-meta {
  margin-left: auto;
  color: var(--muted);
}

/* 输入区 */
.composer-wrap {
  padding: 0 24px 18px;
  max-width: 828px;
  margin: 0 auto;
  width: 100%;
}

.key-banner {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  font-size: 12px;
  color: #8a6410;
  background: #fff9ec;
  border: 1px solid #f5e2b3;
  border-radius: 10px;
  padding: 9px 12px;
  margin-bottom: 10px;
  line-height: 1.6;
}

.key-banner code {
  background: #fdf1d6;
  padding: 0 4px;
  border-radius: 4px;
  font-size: 11.5px;
}

/* 演示环境说明条：和「缺少 Key」区分开，避免误以为模型在回答 */
.demo-banner {
  color: #2f5c8a;
  background: #eef5ff;
  border-color: #cfe2fb;
}

.demo-banner strong {
  color: #1f4a78;
}

.composer {
  border: 1px solid #dfe3ea;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 4px 18px rgba(24, 39, 75, 0.05);
  transition: border-color 0.15s, box-shadow 0.15s;
}

.composer:focus-within {
  border-color: #b9c9f4;
  box-shadow: 0 6px 22px rgba(47, 107, 255, 0.1);
}

.composer-input {
  width: 100%;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  padding: 16px 18px 6px;
  font-size: 14px;
  line-height: 1.65;
  font-family: inherit;
  color: var(--ink);
  min-height: 52px;
  max-height: 180px;
}

.composer-input::placeholder {
  color: #aeb4bd;
}

.composer-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px 10px;
}

.model-picker {
  position: relative;
}

.model-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border-radius: 8px;
  border: 1px solid var(--line);
  background: #fff;
  font-size: 12.5px;
  color: #3d434b;
  cursor: pointer;
}

.model-chip:hover {
  border-color: #c9d4ec;
  color: var(--accent);
}

.chip-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #34c759;
}

.chip-dot.off {
  background: #f0a020;
}

.caret-icon {
  font-size: 10px;
  color: var(--muted);
}

.model-backdrop {
  position: fixed;
  inset: 0;
  z-index: 40;
}

.model-panel {
  position: absolute;
  bottom: 40px;
  left: 0;
  width: 320px;
  max-height: 380px;
  overflow-y: auto;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 14px;
  box-shadow: 0 16px 40px rgba(24, 39, 75, 0.16);
  padding: 8px;
  z-index: 41;
}

.panel-title {
  font-size: 11.5px;
  color: var(--muted);
  padding: 6px 8px;
  letter-spacing: 0.4px;
}

.model-group + .model-group {
  margin-top: 4px;
  border-top: 1px solid #f2f3f6;
  padding-top: 4px;
}

.group-name {
  font-size: 11px;
  color: #a6adb8;
  padding: 6px 8px 2px;
}

.model-item {
  width: 100%;
  text-align: left;
  border: none;
  background: transparent;
  border-radius: 9px;
  padding: 7px 8px;
  cursor: pointer;
}

.model-item:hover {
  background: #f5f7fb;
}

.model-item.selected {
  background: var(--accent-soft);
}

.model-item.disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.mi-main {
  display: flex;
  align-items: center;
  gap: 6px;
}

.mi-name {
  font-size: 13px;
  font-weight: 500;
  color: #22272e;
}

.mi-tag {
  font-size: 10.5px;
  color: #1a7f4b;
  background: #e8f7ee;
  border-radius: 20px;
  padding: 1px 6px;
}

.mi-off {
  font-size: 10.5px;
  color: #b0700d;
  background: #fff4dd;
  border-radius: 20px;
  padding: 1px 6px;
}

.mi-note {
  font-size: 11.5px;
  color: var(--muted);
  margin-top: 2px;
  line-height: 1.5;
}

.panel-foot {
  border-top: 1px solid #f2f3f6;
  margin-top: 6px;
  padding: 8px;
  font-size: 11px;
  color: #a6adb8;
  line-height: 1.6;
}

.panel-foot code {
  background: #f4f5f8;
  border-radius: 4px;
  padding: 0 3px;
}

.composer-hint {
  font-size: 11.5px;
  color: #b3b9c2;
  margin-left: auto;
}

.send-btn {
  height: 32px;
  padding: 0 18px;
  border-radius: 9px;
  border: none;
  background: var(--accent);
  color: #fff;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s;
}

.send-btn:hover:not(:disabled) {
  background: #245ae0;
}

.send-btn:disabled {
  background: #d6dbe4;
  cursor: not-allowed;
}

.send-btn.stop {
  background: #fff;
  border: 1px solid #f0c9c9;
  color: #c0392b;
}

/* 工具箱 */
.tool-desc {
  font-size: 12.5px;
  color: var(--muted);
  margin-bottom: 14px;
}

.tool-grid {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: 16px;
}

.tool-form {
  border-right: 1px solid var(--line);
  padding-right: 16px;
}

.tool-result {
  min-width: 0;
}

.tool-table {
  margin-top: 12px;
}

.predict-list {
  margin-top: 18px;
  max-height: 380px;
  overflow-y: auto;
}

.predict-row {
  display: flex;
  justify-content: space-between;
  padding: 9px 12px;
  border-radius: 8px;
  background: #f8f9fb;
  margin-bottom: 6px;
  font-size: 13px;
}

.predict-row .down {
  color: #1a7f4b;
  font-weight: 600;
}

.predict-row .up {
  color: #c0392b;
  font-weight: 600;
}

.today-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin-bottom: 18px;
}

.today-item {
  background: #f8f9fb;
  border-radius: 10px;
  padding: 12px;
  text-align: center;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.today-item strong {
  font-size: 20px;
  color: var(--accent);
}

.today-item span {
  font-size: 11.5px;
  color: var(--muted);
}

.suggest-row {
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 8px;
  font-size: 13px;
}

.suggest-row strong {
  display: block;
  margin-bottom: 4px;
  font-size: 13px;
}

.suggest-row p {
  color: #5a616b;
  line-height: 1.65;
}

.suggest-row.success { background: #f0f9f3; }
.suggest-row.warning { background: #fdf7ec; }
.suggest-row.danger { background: #fdf0ef; }
.suggest-row.info { background: #f2f6ff; }

@media (max-width: 1100px) {
  .sessions {
    width: 200px;
    flex-basis: 200px;
  }
  .suggest-grid {
    grid-template-columns: 1fr;
  }
}
</style>
