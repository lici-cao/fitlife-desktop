/**
 * P4 执行闭环相关类型：与服务端 StatsSummaryVO / ProgressAdviceVO / TodayPlanVO 对齐。
 */

export interface DailyPoint {
  date: string
  plannedItems: number
  doneItems: number
  adherenceRate: number
  minutes: number
  burn: number
  intake: number
  net: number
  avgRpe: number | null
}

export interface RiskNote {
  level: 'DANGER' | 'WARN' | 'INFO' | string
  code: string
  message: string
  suggestion: string
}

export interface StatsSummary {
  startDate: string
  endDate: string
  days: number
  activeDays: number
  plannedItems: number
  doneItems: number
  skippedItems: number
  adherenceRate: number
  streakDays: number
  totalMinutes: number
  totalSessions: number
  totalBurnCalories: number
  totalIntakeCalories: number
  netCalories: number
  avgNetCalories: number
  avgWeeklyMinutes: number
  avgActualRpe: number | null
  avgPlannedRpe: number | null
  rpeDeviation: number | null
  painDays: number
  targetCalories: number
  series: DailyPoint[]
  risks: RiskNote[]
}

export type AdvanceDecision = 'HOLD' | 'DELOAD' | 'PROGRESS' | 'SWAP' | 'STOP'

export interface ProgressAdvice {
  decision: AdvanceDecision | string
  title: string
  reasons: string[]
  suggestions: string[]
  volumeChangePct: number
  canAutoApply: boolean
  riskLevel: string
  adherenceRate: number
  rpeDeviation: number | null
  painDays: number
  plannedItems: number
  doneItems: number
  windowDays: number
  basePlanId: number | null
  baseVersion: number | null
  newPlanId?: number | null
  newVersion?: number | null
  note: string
}

export interface TodayPlanItem {
  planDetailId: number
  exerciseId: number | null
  exerciseName: string
  muscleGroup: string
  category: string
  sets: number | null
  reps: string | null
  restSec: number | null
  rpe: number | null
  seq: number | null
  estCalories: number | null
  description: string | null
  contraindication: string | null
  done: boolean
  status: string | null
  actualMinutes: number | null
  actualCalories: number | null
  actualRpe: number | null
  warningLevel: string | null
  warningText: string | null
}

export interface TodayPlan {
  planId: number | null
  version: number | null
  planTitle: string | null
  planDay: number
  dayLabel: string
  focus: string | null
  restDay: boolean
  plannedItems: number
  doneItems: number
  adherenceRate: number
  minutes: number
  calories: number
  hrLow: number | null
  hrHigh: number | null
  hrNote: string | null
  items: TodayPlanItem[]
}