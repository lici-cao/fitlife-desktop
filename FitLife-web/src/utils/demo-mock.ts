/**
 * 演示预览数据层（仅开发预览使用，不参与正式运行）
 *
 * 触发条件：main.ts 中同时满足
 *   1) import.meta.env.DEV（npm run dev 才为 true，npm run build 后恒为 false）
 *   2) localStorage.fitlife_demo === '1'（由 /demo.html 演示入口写入）
 *
 * 作用：本机没有 MySQL / Spring Boot 时，用 100 条真实动作库种子数据
 * 在浏览器里模拟后端接口，便于直接查看「动作库 / 训练计划 / AI 健身搭子」
 * 的完整界面与交互。正式启动后端时该层完全不生效。
 */
import type { AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios'

type RawExercise = {
  name: string
  category: string
  muscle_group: string
  equipment: string
  difficulty: number
  met_value: number
  default_sets: number
  default_reps: string
  description: string
  contraindication: string
}

type ExerciseDTO = {
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

let cache: ExerciseDTO[] | null = null

async function loadExercises(): Promise<ExerciseDTO[]> {
  if (cache) return cache
  try {
    const res = await fetch('/demo/exercises.json')
    const raw: RawExercise[] = await res.json()
    cache = raw.map((e, i) => ({
      id: i + 1,
      name: e.name,
      category: e.category,
      muscleGroup: e.muscle_group,
      equipment: e.equipment,
      difficulty: Number(e.difficulty),
      metValue: Number(e.met_value),
      defaultSets: Number(e.default_sets),
      defaultReps: e.default_reps,
      description: e.description,
      contraindication: e.contraindication
    }))
  } catch {
    cache = []
  }
  return cache
}

const ok = (data: any) => ({ code: 200, success: true, message: 'success', data })

function statsOf(list: ExerciseDTO[]) {
  const byCategory: Record<string, number> = {}
  const byMuscleGroup: Record<string, number> = {}
  for (const e of list) {
    byCategory[e.category] = (byCategory[e.category] || 0) + 1
    byMuscleGroup[e.muscleGroup] = (byMuscleGroup[e.muscleGroup] || 0) + 1
  }
  return {
    total: list.length,
    byCategory,
    byMuscleGroup,
    equipments: Array.from(new Set(list.map(e => e.equipment)))
  }
}

function at(list: ExerciseDTO[], i: number): ExerciseDTO | null {
  if (!list.length) return null
  return list[((i % list.length) + list.length) % list.length]
}

const calories = (met: number, minutes: number, weight = 60) =>
  Math.round(met * 3.5 * weight / 200 * minutes)

/** 热身 → 主项 → 辅助 → 核心 → 有氧 → 拉伸，动作全部来自动作库 */
function phasesOf(list: ExerciseDTO[], seed: number) {
  const by = (c: string) => list.filter(e => e.category === c)
  const strength = by('力量')
  const core = by('核心')
  const cardio = by('有氧')
  const mobility = by('柔韧')
  const plan = [
    { e: at(mobility, seed), phase: '热身', sets: 1, reps: '3分钟', minutes: 3, rest: 0 },
    { e: at(strength, seed * 3), phase: '主项', sets: 3, reps: '10-12次', minutes: 9, rest: 60 },
    { e: at(strength, seed * 3 + 1), phase: '主项', sets: 3, reps: '12次', minutes: 9, rest: 60 },
    { e: at(strength, seed * 3 + 2), phase: '辅助', sets: 3, reps: '12-15次', minutes: 8, rest: 45 },
    { e: at(core, seed), phase: '核心', sets: 3, reps: '30秒', minutes: 6, rest: 30 },
    { e: at(cardio, seed), phase: '有氧', sets: 1, reps: '10分钟', minutes: 10, rest: 0 },
    { e: at(mobility, seed + 1), phase: '拉伸', sets: 1, reps: '3分钟', minutes: 3, rest: 0 }
  ]
  return plan.filter(p => p.e !== null) as {
    e: ExerciseDTO; phase: string; sets: number; reps: string; minutes: number; rest: number
  }[]
}

function matchOf(list: ExerciseDTO[], seed = 0, goal = '减脂') {
  const phases = phasesOf(list, seed)
  const items = phases.map((p, i) => ({
    exerciseId: p.e.id,
    name: p.e.name,
    category: p.e.category,
    muscleGroup: p.e.muscleGroup,
    equipment: p.e.equipment,
    difficulty: p.e.difficulty,
    metValue: p.e.metValue,
    phase: p.phase,
    sets: p.sets,
    reps: p.reps,
    restSec: p.rest,
    minutes: p.minutes,
    estCalories: calories(p.e.metValue, p.minutes),
    description: p.e.description,
    contraindication: p.e.contraindication,
    seq: i + 1
  }))
  const totalMinutes = items.reduce((s, it) => s + it.minutes, 0)
  return {
    summary: `围绕「${goal}」目标，按热身激活 → 复合主项 → 孤立辅助 → 核心稳定 → 低强度有氧 → 拉伸放松的顺序编排。动作全部取自系统动作库，组次与间歇已按初级水平设定；若某个动作有禁忌提示，请替换为同肌群的替代动作。`,
    focus: '热身 → 主项 → 辅助 → 核心 → 有氧 → 拉伸',
    totalMinutes,
    estCalories: items.reduce((s, it) => s + it.estCalories, 0),
    aiGenerated: true,
    note: '演示预览数据：正式运行时由大模型结合动作库检索生成，AI 不可用时自动降级为规则引擎。',
    items
  }
}

const DAY_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

function planOf(list: ExerciseDTO[], goal = '减脂', experience = '初级', sessionMinutes = 45) {
  const focuses = ['全身基础 + 核心', '上肢推拉 + 有氧', '下肢臀腿 + 核心']
  let seed = 0
  const days = DAY_LABELS.map((label, i) => {
    const dayOfWeek = i + 1
    if (![1, 3, 5].includes(dayOfWeek)) {
      return {
        dayOfWeek,
        dayLabel: label,
        focus: '',
        restDay: true,
        estMinutes: 0,
        estCalories: 0,
        items: []
      }
    }
    const items = phasesOf(list, seed).map((p, idx) => ({
      exerciseId: p.e.id,
      exerciseName: p.e.name,
      muscleGroup: p.e.muscleGroup,
      category: p.e.category,
      sets: p.sets,
      reps: p.reps,
      restSec: p.rest,
      rpe: 6,
      seq: idx + 1,
      estCalories: calories(p.e.metValue, p.minutes),
      description: p.e.description
    }))
    seed += 1
    return {
      dayOfWeek,
      dayLabel: label,
      focus: focuses[(seed - 1) % focuses.length],
      restDay: false,
      estMinutes: sessionMinutes,
      estCalories: items.reduce((s: number, it: any) => s + it.estCalories, 0),
      items
    }
  })
  const weeklyCalories = days.reduce((s: number, d: any) => s + d.estCalories, 0)
  return {
    id: 1,
    title: `${goal} · 每周 3 练计划`,
    goal,
    weeklyDays: 3,
    sessionMinutes,
    summary: `依据你的「${experience}」水平与「${goal}」目标，安排每周 3 次训练、4 天主动恢复。每次训练按热身、复合主项、辅助、核心、有氧、拉伸推进，所有动作均来自系统动作库并附带组次、间歇与动作要领。`,
    weeklyCardioMin: 30,
    weeklyCalories,
    aiGenerated: true,
    note: '演示预览数据：当前未连接后端，展示的是动作库真实种子数据编排出的计划结构。',
    status: 'ACTIVE',
    createdAt: new Date().toISOString(),
    days
  }
}

function historyOf(goal = '减脂') {
  return [
    { id: 1, title: `${goal} · 每周 3 练计划`, goal, weeklyDays: 3, status: 'ACTIVE', createdAt: new Date().toISOString() },
    { id: 2, title: '全身适应 · 每周 2 练计划', goal: '塑形', weeklyDays: 2, status: 'ARCHIVED', createdAt: '2026-10-20T09:00:00' },
    { id: 3, title: '基础体能 · 每周 3 练计划', goal: '体能', weeklyDays: 3, status: 'ARCHIVED', createdAt: '2026-10-06T09:00:00' }
  ]
}

async function resolve(url: string, method: string, body: any, params: any): Promise<any> {
  const list = await loadExercises()

  if (url.startsWith('/auth/')) {
    return ok({ token: 'demo-token', userId: 1, username: body?.username || 'dandan' })
  }

  if (url === '/exercise/list') {
    const filtered = list.filter(e =>
      (!params?.category || e.category === params.category) &&
      (!params?.muscleGroup || e.muscleGroup === params.muscleGroup) &&
      (!params?.equipment || e.equipment === params.equipment) &&
      (params?.maxDifficulty == null || e.difficulty <= Number(params.maxDifficulty))
    )
    return ok(filtered)
  }
  if (url === '/exercise/stats') return ok(statsOf(list))
  if (/^\/exercise\/\d+$/.test(url)) {
    const found = list.find(e => String(e.id) === url.split('/').pop())
    return found ? ok(found) : { code: 400, success: false, message: '动作不存在', data: null }
  }

  if (url === '/plan/current') return ok(planOf(list))
  if (url === '/plan/list') return ok(historyOf())
  if (/^\/plan\/\d+$/.test(url)) return ok(planOf(list))
  if (url === '/plan/generate') return ok(planOf(list, body?.goal || '减脂', body?.experience || '初级', body?.sessionMinutes || 45))
  if (url === '/plan/match') return ok(matchOf(list, 0, body?.goal || '减脂'))
  if (url === '/plan/chat') {
    const msg: string = body?.message || ''
    if (/计划|一周|周计划|安排|生成/.test(msg)) {
      return ok({
        action: 'PLAN_CREATED',
        reply: `已按你的需求生成并保存一份一周训练计划：每周 3 练、每次 45 分钟，动作全部来自系统动作库（共 ${list.length} 个已审核动作），已自动避开禁忌部位。下面是计划概览，点按钮可以查看每天的具体安排。`,
        plan: planOf(list),
        match: null
      })
    }
    if (/搭配|怎么练|动作|练胸|练背|练腿|训练|推荐/.test(msg)) {
      const m = matchOf(list, 1)
      return ok({
        action: 'MATCH',
        reply: `给你搭了一组 ${m.totalMinutes} 分钟的训练，按热身 → 主项 → 辅助 → 核心 → 有氧 → 拉伸排列，每个动作都带组次、间歇和动作要领，全部取自动作库真实数据。`,
        plan: null,
        match: m
      })
    }
    return ok({
      action: 'CHAT',
      reply: `收到！我是你的 AI 健身搭子。现在可以直接对我说「帮我生成一周减脂计划，只有哑铃，膝盖不好」，或「今天练胸怎么搭配」——我会基于系统动作库（现有 ${list.length} 个动作）生成可落库的训练计划或一组带组次的动作搭配。\n\n日常饮食、体重趋势、热量缺口的问题也都可以问我。`,
      plan: null,
      match: null
    })
  }

  if (url === '/ai/chat') {
    const msg: string = body?.message || ''
    return ok({
      reply: `关于「${msg}」，结合你当前的减脂目标，建议优先保证每周 3 次规律训练与每日 300–500 kcal 的热量缺口。下面这些动作都来自系统动作库，可以直接点击查看要领：`,
      matchedExercises: list.filter(e => e.difficulty <= 2).slice(0, 3),
      action: 'GO_MATCH'
    })
  }
  if (url.startsWith('/ai/')) {
    return ok({
      prediction: '按当前热量缺口，预计 4 周后体重约下降 1.6 kg',
      analysis: '当前摄入低于目标热量，蛋白质摄入建议提高到每日 95 g。',
      suggestion: '建议增加 2 次力量训练，并把有氧安排在力量训练之后。',
      summary: '今日热量缺口约 420 kcal，保持得不错，注意训练后补充蛋白质。',
      advice: ['保证 7 小时睡眠', '训练日蛋白质 1.6–2.2 g/kg', '每 4 周复盘一次体重趋势'],
      reply: '演示预览：当前未连接 AI 服务，正式运行时这里会返回大模型结合动作库生成的个性化建议。'
    })
  }

  if (url.startsWith('/data/')) {
    return ok({ date: new Date().toISOString().split('T')[0], foodIntake: 1180, exerciseBurn: 320, targetCalories: 1500, weight: 60 })
  }

  if (url === '/chat/models') {
    const mk = (id: string, name: string, providerId: string, providerName: string, tag: string, note: string, isDefault = false) => ({
      id, name, providerId, providerName, tag, free: true, available: true, isDefault, note
    })
    return ok({
      defaultModel: 'deepseek-chat',
      available: true,
      models: [
        mk('deepseek-chat', 'DeepSeek V3.2', 'deepseek', 'DeepSeek', '免费额度', '通用对话与计划编排，速度快', true),
        mk('deepseek-reasoner', 'DeepSeek R1', 'deepseek', 'DeepSeek', '推理', '复杂问题逐步推理'),
        mk('doubao-seed-1-6-flash', '豆包 Seed 1.6 Flash', 'doubao', '豆包', '免费额度', '极速低价，日常问答够用'),
        mk('doubao-seed-1-6', '豆包 Seed 1.6', 'doubao', '豆包', '综合', '综合能力更强，支持更长上下文'),
        mk('glm-4-flash', 'GLM-4-Flash', 'glm', '智谱 GLM', '免费', '免费兜底模型')
      ]
    })
  }

  // 其余接口统一返回成功空数据，避免演示页面报错
  return ok(method === 'get' ? null : {})
}

/** 把 axios 请求替换为本地演示响应，完全不访问网络 */
export function installDemoMock(instance: AxiosInstance): void {
  instance.defaults.adapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    const url = (config.url || '').split('?')[0]
    const method = (config.method || 'get').toLowerCase()
    let body: any = {}
    if (typeof config.data === 'string' && config.data) {
      try { body = JSON.parse(config.data) } catch { body = {} }
    } else if (config.data && typeof config.data === 'object') {
      body = config.data
    }
    // 模拟一点网络延迟，让 loading 态可见
    await new Promise(resolve => setTimeout(resolve, 180 + Math.random() * 220))
    const data = await resolve(url, method, body, config.params || {})
    return {
      data,
      status: 200,
      statusText: 'OK',
      headers: {},
      config
    } as unknown as AxiosResponse
  }
}
