# 🏋️ FitLife - 健身饮食管理应用

一款基于 **Vue 3 + Spring Boot** 的现代化健身饮食管理应用，帮助用户追踪饮食、记录运动、管理健康档案，并配备 **AI 健身搭子** 提供智能建议。

---

## ✨ 功能特性

### 核心功能

| 模块 | 功能 | 说明 |
|------|------|------|
| 👤 **用户认证** | 注册 / 登录 | JWT Token 认证，安全可靠 |
| 📋 **健康档案** | 个人数据管理 | 性别、年龄、身高、体重、体脂率、目标、活动水平 |
| 🍎 **饮食记录** | 食物摄入追踪 | 自定义分量、多单位支持、热量计算 |
| 🏃 **运动记录** | 运动消耗追踪 | 记录运动项目、时长、消耗热量 |
| 📊 **数据仪表盘** | 每日数据可视化 | 摄入 / 消耗 / 目标 一目了然 |
| 🧮 **热量计算器** | BMR / TDEE 计算 | 根据个人数据计算每日所需热量 |
| 🏋️ **动作库** | 已审核动作检索 | 100 个动作，含肌群、器械、难度、MET、动作要领与禁忌症 |
| 📅 **训练计划** | AI 一周计划生成 | 基于动作库编排、结构化落库、可查看每天组次 |
| 🤖 **AI 健身搭子** | 对话即执行 | 聊天直接生成计划、智能运动搭配、体重预测与饮食建议 |

---

## 🏗️ 技术架构

### 前端技术栈

```
Vue 3 + TypeScript + Vite
├── Element Plus         # UI 组件库
├── Pinia                # 状态管理
├── Vue Router           # 路由管理
├── Axios                # HTTP 请求
├── ECharts              # 图表可视化
└── @vueuse/core         # 工具函数库
```

### 后端技术栈

```
Spring Boot 3.2
├── Spring Security      # 安全框架
├── Spring Data JPA      # 数据持久化
├── JWT (jjwt)           # 身份认证
├── Lombok               # 简化代码
├── SQLite / MySQL       # 数据库
└── 国内 AI API          # 智能对话服务
```

---

## 📁 项目结构

### 前端项目结构

```
vite-project/
├── src/
│   ├── api/
│   │   ├── index.ts           # 基础 API 服务层（认证/数据/食物/运动）
│   │   └── ai.ts              # AI 健身搭子 API 服务
│   ├── stores/
│   │   ├── app.ts             # 应用状态
│   │   ├── user.ts            # 用户状态管理
│   │   ├── food.ts            # 食物状态管理
│   │   └── exercise.ts        # 运动状态管理
│   ├── views/
│   │   ├── Login.vue          # 登录页
│   │   ├── Register.vue       # 注册页
│   │   ├── Dashboard.vue      # 数据仪表盘
│   │   ├── Profile.vue        # 健康档案
│   │   ├── Food.vue           # 饮食记录（支持自定义分量）
│   │   ├── Exercise.vue       # 运动记录
│   │   ├── Calculator.vue     # 热量计算器
│   │   ├── ExerciseLibrary.vue # 动作库（100 个已审核动作）
│   │   ├── Plan.vue           # AI 训练计划生成与查看
│   │   └── AICompanion.vue    # AI 健身搭子
│   ├── router/
│   │   └── index.ts           # 路由配置（带权限守卫）
│   ├── utils/
│   │   └── http.ts            # Axios HTTP 工具类
│   ├── App.vue                # 根组件
│   ├── main.ts                # 入口文件
│   └── style.css              # 全局样式
├── public/
│   ├── favicon.svg
│   └── icons.svg
├── SpringBoot_API_Doc.md      # Spring Boot 后端接口文档
├── README.md                  # 项目说明
├── vite.config.ts             # Vite 配置（含代理）
├── tsconfig.json
└── package.json
```

---

## 🚀 快速开始

### 环境要求

| 工具 | 版本要求 |
|------|---------|
| Node.js | >= 18.x |
| npm | >= 9.x |
| Java | >= 17 |
| Spring Boot | >= 3.2 |

### 1. 前端启动

```bash
# 进入前端目录
cd vite-project

# 安装依赖
npm install

# 开发模式（默认 http://localhost:5173）
npm run dev

# 生产构建
npm run build

# 预览构建结果
npm run preview
```

### 2. 后端启动

```bash
# 进入 Spring Boot 项目目录
# 详见 SpringBoot_API_Doc.md

# 使用 Maven 启动
mvn spring-boot:run

# 或打包后运行
mvn package
java -jar target/fitlife-1.0.0.jar

# 后端服务地址: http://localhost:8080
```

---

## 🔌 API 接口概览

### 认证接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 用户注册 |
| POST | `/api/auth/login` | 用户登录 |

### 数据接口（需要 Token）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/data/profile` | 获取用户档案 |
| PUT | `/api/data/profile` | 更新用户档案 |
| GET | `/api/data/daily-record/today` | 获取今日记录 |
| GET | `/api/data/daily-records` | 获取每日记录列表 |
| POST | `/api/data/food-intake` | 添加食物摄入 |
| POST | `/api/data/exercise-burn` | 添加运动消耗 |
| GET | `/api/data/workout-records` | 获取运动记录列表 |
| GET | `/api/data/custom-foods` | 获取自定义食物 |
| POST | `/api/data/custom-foods` | 添加自定义食物 |
| DELETE | `/api/data/custom-foods/{id}` | 删除自定义食物 |
| GET | `/api/data/sync` | 同步所有数据 |

### AI 接口（需要 Token）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/ai/chat` | 智能聊天对话 |
| POST | `/api/ai/predict-weight` | 体重变化预测 |
| POST | `/api/ai/diet-suggestion` | 饮食智能建议 |
| POST | `/api/ai/exercise-suggestion` | 运动智能建议 |
| POST | `/api/ai/daily-summary` | 每日总结 |

### 健康检查

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 服务健康状态检查 |

> 📖 **完整接口文档**：请查看 [SpringBoot_API_Doc.md](./SpringBoot_API_Doc.md)

---

## 🔐 认证方式

### JWT Token

所有 `/api/data/**` 和 `/api/ai/**` 接口需在请求头中携带 Bearer Token：

```
Authorization: Bearer <your-jwt-token>
```

### Token 存储

前端使用 localStorage 存储：

```typescript
// 登录成功后保存
localStorage.setItem('fitlife_token', response.data.data.token)
localStorage.setItem('fitlife_userId', response.data.data.user.id)

// 退出登录时清除
localStorage.removeItem('fitlife_token')
localStorage.removeItem('fitlife_userId')
```

### 路由权限守卫

所有需要登录的页面在路由元信息中标记：

```typescript
{
  path: '/dashboard',
  name: 'Dashboard',
  component: () => import('../views/Dashboard.vue'),
  meta: { requiresAuth: true }  // 需要登录
}
```

---

## 🧮 热量计算公式

### BMR（基础代谢率）- Mifflin-St Jeor 公式

```
男性: BMR = 10 × 体重(kg) + 6.25 × 身高(cm) - 5 × 年龄 + 5
女性: BMR = 10 × 体重(kg) + 6.25 × 身高(cm) - 5 × 年龄 - 161
```

### TDEE（每日总能量消耗）

| 活动水平 | 系数 |
|---------|------|
| 久坐 (sedentary) | 1.2 |
| 轻度活动 (light) | 1.375 |
| 中等活动 (moderate) | 1.55 |
| 高度活动 (active) | 1.725 |
| 非常活跃 (very_active) | 1.9 |

```
TDEE = BMR × 活动系数
```

### 目标热量

| 目标 | 热量调整 |
|------|---------|
| 减脂 (fat_loss) | TDEE - 500 |
| 维持 (maintain) | TDEE |
| 增肌 (muscle_gain) | TDEE + 300 |

---

## 🤖 AI 健身搭子

### 功能说明

AI 健身搭子提供以下智能服务：

1. **💬 智能聊天** - 随时随地咨询健身饮食问题
2. **📈 体重预测** - 根据当前习惯预测未来体重变化
3. **🥗 饮食建议** - 基于当前摄入提供个性化建议
4. **🏋️ 运动建议** - 根据目标推荐运动方案
5. **📝 每日总结** - 自动分析当日健康数据

### 上下文数据

每次对话时前端会自动携带用户上下文数据：

```json
{
  "message": "我今天吃多了怎么办？",
  "context": {
    "userProfile": {
      "name": "张三",
      "gender": "female",
      "age": 25,
      "weight": 60,
      "height": 165,
      "bodyFat": 25,
      "goal": "fat_loss",
      "activityLevel": "moderate"
    },
    "todayData": {
      "foodIntake": 1850,
      "exerciseBurn": 300,
      "targetCalories": 1686
    },
    "bmr": 1410,
    "tdee": 2186,
    "targetCalories": 1686
  }
}
```

### 降级策略

当后端 AI 服务不可用时（超时 / 500 错误），前端会自动使用模拟回复数据，保证用户体验不中断。

---

## 🍎 饮食记录 - 自定义分量

添加食物时支持自定义分量，更加灵活准确：

### 支持的单位

- 克 (g)
- 毫升 (ml)
- 个 / 份
- 杯 (cup)
- 勺 (tbsp / tsp)
- 碗 (bowl)

### 热量计算

```
实际热量 = (食物基础热量 / 基础份量) × 用户输入份量
```

**示例**：一份 200 克的鸡胸肉沙拉（基础热量 250 大卡 / 200 克）
```
用户输入: 150 克
实际热量 = (250 / 200) × 150 = 187.5 大卡 ≈ 188 大卡
```

---

## 📊 数据同步

首次登录后调用 `/api/data/sync` 接口一次性获取所有用户数据：

```
profile        → 用户档案
todayRecord    → 今日记录
customFoods    → 自定义食物列表
workoutRecords → 运动记录列表
dailyRecords   → 每日历史记录
```

---

## ⚙️ 开发配置

### Vite 代理配置（开发环境）

**文件**：`vite.config.ts`

```typescript
server: {
  proxy: {
    '/api': {
      target: 'http://localhost:8080',
      changeOrigin: true
    }
  }
}
```

开发环境下，所有 `/api/**` 请求会被转发到后端 `http://localhost:8080`。

### Axios 配置

**文件**：`src/utils/http.ts`

```typescript
// 开发环境使用相对路径（由 Vite 代理转发）
// 生产环境可通过环境变量配置
const BASE_URL = import.meta.env.VITE_APP_API_URL || '/api'

// 请求超时 30 秒（AI 接口响应较慢）
timeout: 30000
```

---

## 🛣️ 前端路由

| 路径 | 页面 | 是否需要登录 |
|------|------|------------|
| `/` | 重定向到登录页 | ❌ |
| `/login` | 登录页 | ❌ |
| `/register` | 注册页 | ❌ |
| `/dashboard` | 数据仪表盘 | ✅ |
| `/profile` | 健康档案 | ✅ |
| `/food` | 饮食记录 | ✅ |
| `/exercise` | 运动记录 | ✅ |
| `/exercise-library` | 动作库 | ✅ |
| `/plan` | 训练计划 | ✅ |
| `/calculator` | 热量计算器 | ✅ |
| `/ai-companion` | AI 健身搭子 | ✅ |

---

## 📦 依赖版本

### 核心依赖

```json
{
  "vue": "^3.5.34",
  "vue-router": "^5.1.0",
  "pinia": "^3.0.4",
  "axios": "^1.17.0",
  "element-plus": "^2.14.1",
  "@element-plus/icons-vue": "^2.3.2",
  "echarts": "^6.1.0",
  "@vueuse/core": "^14.3.0"
}
```

---

## ❌ 常见问题

### Q1: 前端启动后无法连接后端？

**A**: 检查以下几点：
- 确认 Spring Boot 服务已启动并监听 8080 端口
- 浏览器访问 `http://localhost:8080/api/health` 确认返回 `ok`
- 检查 `vite.config.ts` 中的代理配置

### Q2: AI 健身搭子一直用模拟回复？

**A**: 请检查：
- 后端 `/api/ai/*` 接口是否已实现
- 后端 AI 服务（国内 AI API）是否已正确配置
- 控制台是否有 HTTP 500 / timeout 错误

### Q3: Token 过期自动跳转登录页？

**A**: HTTP 拦截器已自动处理：
- 检测到 401 / 403 状态码时自动清除本地 Token
- 自动重定向到 `/login` 页面

### Q4: 如何修改为生产环境地址？

**A**: 创建 `.env.production` 文件：
```
VITE_APP_API_URL = https://your-api-domain.com/api
```

---

## 📖 更多文档

- 📄 [Spring Boot 后端接口详细文档](./SpringBoot_API_Doc.md)
- 🏗️ 包含完整的后端实现示例代码
- 📋 包含数据库表结构设计
- 🔐 包含 JWT 认证实现示例

---

## 📄 License

FitLife - 让健康管理更简单 💪

