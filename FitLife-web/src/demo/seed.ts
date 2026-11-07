/**
 * 云端演示种子数据
 *
 * 仅在 `npm run build:demo`（vite --mode demo，VITE_DEMO=1）时生效：
 * 打开页面即写入演示账号 / 档案 / 近 7 天记录，并打上 fitlife_demo 标记，
 * 让 demo-mock 接管全部接口请求。正式构建（npm run build）不会执行这里的任何代码。
 */

const seedDemoData = () => {
  if (localStorage.getItem('fitlife_demo') === '1') return

  localStorage.setItem('fitlife_demo', '1')
  localStorage.setItem('fitlife_token', 'demo-token')
  localStorage.setItem('fitlife_userId', '1')
  localStorage.setItem('fitlife_isLoggedIn', 'true')
  localStorage.setItem(
    'fitlife_profile',
    JSON.stringify({
      name: '丹丹',
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
      equipments: '徒手,哑铃',
      injuries: ''
    })
  )

  const intake = [1320, 1450, 1180, 1520, 1290, 1610, 1180]
  const burn = [260, 180, 420, 150, 330, 210, 320]
  const records = []
  for (let i = 6; i >= 0; i--) {
    const d = new Date()
    d.setDate(d.getDate() - i)
    records.push({
      date: d.toISOString().split('T')[0],
      foodIntake: intake[6 - i],
      exerciseBurn: burn[6 - i],
      targetCalories: 1500,
      weight: Number((60.8 - (6 - i) * 0.12).toFixed(1))
    })
  }
  localStorage.setItem('fitlife_dailyRecords', JSON.stringify(records))
}

if (import.meta.env.VITE_DEMO === '1') {
  seedDemoData()
}

export { seedDemoData }
