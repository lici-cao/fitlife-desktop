# FitLife-project

面向健身人群的一站式健康管理 Web 系统（毕业设计）。

---

## 一、项目简介

系统采用 **Spring Boot 3 + Vue 3** 前后端分离架构，提供饮食记录、运动记录、热量监控、健康数据可视化，以及以**运动动作库为知识源**的 AI 能力。

核心功能：

1. **权限管理**：注册登录、JWT 鉴权、路由守卫，用户数据独立存储。
2. **动作库**：100 条已审核动作，含类别、肌群、器械、难度、MET、动作要领与**禁忌症**标签；支持筛选与检索。
3. **AI 训练计划生成**：按「用户画像 → 规则筛选 → 大模型编排 → 校验兜底」生成一周计划并落库，可查看每天的动作、组次、RPE 与预估热量。
4. **工作台式 AI 对话**：左侧会话列表 + 右侧对话区，支持多轮上下文、逐字流式输出、Markdown 表格/代码渲染、复制与重新生成；
5. **对话即执行**：说「帮我生成一周减脂计划」会真的生成并保存计划；说「今天练胸怎么搭配」会从动作库挑一组结构化动作卡；
6. **模型自选**：对话页底部可切换 DeepSeek V3.2 / R1、豆包 Seed 1.6 Flash / 1.6、GLM-4-Flash / 4.6，未配置 Key 的模型会置灰并给出提示；
7. **智能运动搭配**：按目标/时长/器械/伤病史，从动作库组合出热身→主项→辅助→核心→有氧→拉伸的完整训练，并估算时长与热量（收纳在对话页「工具箱」里）。
8. **数据可视化与记录**：ECharts 热量收支、BMI/BMR/TDEE 测算、饮食与运动记录。

> **v3 变更**：原「健身视频教程」模块已彻底移除（页面、路由、文档），其位置由「动作库 + 训练计划」替代；一键脚本重做，数据库改为幂等迁移且后端自动兜底初始化动作库。
>
> **v4 变更**：AI 对话页重做为工作台式界面（会话列表 / 模型自选 / 流式输出 / Markdown 渲染）；后端接入 DeepSeek 与豆包免费模型，并抽象出统一的 `LlmGateway`，新增厂商只改 `application.yml`。

---

## 二、技术栈

| 层次 | 技术 |
| --- | --- |
| 前端 | Vue 3 + TypeScript + Vite + Element Plus + Pinia + ECharts |
| 后端 | Spring Boot 3.2 + Spring Data JPA + Spring Security + JWT |
| 数据库 | MySQL 8（utf8mb4） |
| AI | 多模型接入：**DeepSeek**、**豆包（火山方舟）**、智谱 GLM，统一走 OpenAI 兼容 `/chat/completions`，支持流式输出与对话页自选模型 |

---

## 三、目录结构

```
FitLife-project-main/
├── FitLife-java/                    后端工程
│   └── src/main/java/com/fitlife/
│       ├── config/
│       │   ├── SecurityConfig.java
│       │   └── ExerciseSeedInitializer.java   ← 启动时自动补齐动作库
│       ├── controller/              PlanController / ExerciseController / AiController ...
│       ├── config/
│       │   └── AiModelProperties.java  多模型配置（DeepSeek / 豆包 / GLM）
│       ├── service/
│       │   ├── LlmGateway.java       统一模型网关（OpenAI 兼容 + SSE 流式）
│       │   ├── ChatService.java      对话编排：意图 → 办事 / 流式问答
│       │   ├── AiService.java        AI 调用 + 动作库检索增强（RAG）
│       │   ├── AiPlanService.java    对话式计划生成 / 智能运动搭配
│       │   └── PlanService.java      计划编排、校验、落库
│       ├── entity/                  Exercise / Plan / PlanDetail / UserProfile ...
│       └── resources/
│           ├── application.yml
│           └── db/exercises.json    100 条动作种子（后端兜底数据源）
├── FitLife-web/
│   └── src/views/
│       ├── ExerciseLibrary.vue      动作库浏览
│       ├── Plan.vue                 训练计划生成与查看
│       └── AICompanion.vue          工作台式对话（会话列表 / 模型自选 / 流式输出）
├── sql/
│   ├── seed_exercises.sql           动作库种子（可单独、重复执行）
│   └── fitlife-reset.sql            全量重建（危险，仅 -ResetDb 调用）
├── fitlife-mysql.sql                幂等建库 / 升级脚本（默认执行）
├── start-fitlife.ps1                一键启动（UTF-8 BOM）
├── start-fitlife.bat                双击运行入口
└── README.md
```

---

## 四、快速启动

### 环境要求

| 依赖 | 版本 |
| --- | --- |
| JDK | 17+ |
| Maven | 3.6+ |
| Node.js | 18+ |
| MySQL | 8.0+（本地 root，脚本默认密码 888） |

### 一键启动（推荐）

双击 `start-fitlife.bat`，或执行：

```powershell
powershell -ExecutionPolicy Bypass -File start-fitlife.ps1
```

脚本依次完成：

1. 检测 JDK / Maven / Node / mysql 客户端（含常见安装目录、phpstudy、xampp 等）；
2. **幂等**导入 `fitlife-mysql.sql`：建库建表、为旧库补画像字段、写入 100 条动作库，**不清空任何已有业务数据**；
3. 查询 `exercises` 表条数做校验，达不到 100 条会提示后端补齐；
4. 首次运行自动 `npm install`（国内镜像）；
5. 把数据库凭据通过环境变量传给后端并启动，轮询 `/api/health` 等待就绪；
6. 启动前端并打开浏览器 `http://localhost:5173`。

常用参数：

```powershell
# 跳过数据库导入（只启动服务）
.\start-fitlife.ps1 -SkipDb

# 自定义 MySQL 凭据 / 客户端路径
.\start-fitlife.ps1 -DbUser root -DbPass 你的密码 -MysqlPath "C:\mysql\bin\mysql.exe"

# 清空并重建数据库（危险！会二次确认）
.\start-fitlife.ps1 -ResetDb

# 改端口 / 不自动开浏览器
.\start-fitlife.ps1 -BackendPort 8081 -FrontendPort 5174 -NoBrowser
```

### 手动启动

```bash
mysql -u root -p --default-character-set=utf8mb4 < fitlife-mysql.sql
cd FitLife-java && mvn spring-boot:run
cd FitLife-web && npm install && npm run dev
```

演示账号：`dandan / 123456`。

### 无需后端的界面预览（答辩演示用）

只想看界面、或当前机器没有 MySQL / JDK 时，可以只启动前端：

```bash
cd FitLife-web && npm install && npm run dev
```

然后打开 `http://localhost:5173/demo.html`。它会写入演示登录态并自动进入首页，也可以加 `?goto=/exercise-library`、`?goto=/plan`、`?goto=/ai-companion` 直达指定页面。

预览所用的数据来自 `public/demo/exercises.json`（与后端 `db/exercises.json` 同源的 100 条真实动作），由 `src/utils/demo-mock.ts` 在浏览器内按后端接口契约模拟响应。**该层只在 `npm run dev` 或演示构建（`VITE_DEMO=1`）且已写入 `fitlife_demo=1` 时启用**，正式启动后端或普通生产构建产物中完全不生效，不改动任何业务逻辑。

### 云端静态演示构建

需要把界面放到线上（无后端、无数据库，纯静态托管）时：

```bash
cd FitLife-web && npm install && npm run build:demo
```

产物在 `FitLife-web/dist-demo`，直接丢到任意静态托管即可访问。与正式构建的差别只有三处，全部由 `.env.demo` 控制：

| 变量 | 作用 |
| --- | --- |
| `VITE_DEMO=1` | 首屏自动写入演示登录态与 7 天记录，接口全部走浏览器内 mock；同时用 `vite-plugin-singlefile` 把 JS/CSS/字体内联成单个 `index.html` |
| `VITE_ROUTER_MODE=hash` | 纯静态空间没有 rewrite，改 hash 路由避免刷新 404 |
| `VITE_OUT_DIR=dist-demo` | 输出到独立目录，不覆盖正式 `dist` |

**为什么打成单文件**：带访问码的分享域名对 `/assets/*.js` 这类子资源请求同样校验 cookie，浏览器把该 cookie 拦掉时（隐私模式、部分内置浏览器）页面就会白屏。内联后整页只有一个 HTML 文档请求，不再依赖任何子资源。

`npm run build` 仍产出接真实后端的正式包，两者互不影响。

---

## 五、数据库说明（重点）

### 为什么之前"数据库里没有动作库"

旧版脚本用 `mysql -e "source C:\...\fitlife-mysql.sql"` 导入，Windows 反斜杠在 `source` 命令里被当作转义字符导致静默失败；而 JPA 的 `ddl-auto: update` 只会建空表、不会灌种子数据，于是 `exercises` 一直为空，计划生成因"没有可用动作"而失败。旧脚本还是 `DROP DATABASE` 全量重建，每次启动都会清空账号与记录。

### 现在的双重保障

1. **SQL 幂等迁移**：`CREATE DATABASE/TABLE IF NOT EXISTS` + 旧表补列 + `INSERT IGNORE` 种子，重复执行安全；只有显式 `-ResetDb` 才执行 `sql/fitlife-reset.sql` 清库。
2. **后端自动兜底**：`ExerciseSeedInitializer` 在应用启动时读取 `db/exercises.json`，只补齐缺失的动作名，绝不覆盖用户数据。**即使完全不执行 SQL，只要能连上 MySQL，动作库也会自动就位。**

### 动作库字段

| 字段 | 说明 |
| --- | --- |
| name | 动作名称（唯一索引） |
| category | 力量 / 有氧 / 核心 / 柔韧 / HIIT |
| muscle_group | 胸 / 背 / 肩 / 手臂 / 腿 / 臀 / 核心 / 全身 |
| equipment | 徒手 / 哑铃 / 杠铃 / 器械 / 弹力带 / 壶铃 |
| difficulty | 1 初级 / 2 中级 / 3 高级 |
| met_value | 代谢当量，按 `MET × 体重(kg) × 时长(h) × 1.05` 估算消耗 |
| default_sets / default_reps | 默认组数与次数/时长 |
| description | 动作要领 |
| contraindication | 禁忌症部位（逗号分隔），进入 Prompt 前会被规则引擎过滤 |

---

## 六、AI 能力链路

### 0. 工作台式对话（`/api/chat/stream`）

对话页是整个系统的 AI 入口，界面按「会话列表 + 对话区 + 模型选择器」组织：

```
用户输入
   ↓ POST /api/chat/stream（SSE）
LlmGateway.resolve(模型 id)          ← DeepSeek / 豆包 / GLM，Key 未配置则置灰
   ↓
AiPlanService.tryAction()             ← 先看是不是「生成计划 / 运动搭配」
   ├─ 命中 → PlanService 规则筛选 + 大模型编排 → 落库 → 推送 plan / match 卡片
   └─ 未命中 → 动作库检索增强（RAG）→ 大模型流式输出 → 解析【动作ID】→ 推送动作卡
   ↓
SSE 事件：meta → delta* → (plan|match|exercises) → done
```

- **流式输出**：后端用 `HttpURLConnection` 读厂商的 `data:` 分片，再通过 `SseEmitter` 转发；前端用 `fetch + ReadableStream` 逐块解析，支持中途「停止」。
- **多轮上下文**：前端把最近 10 轮消息随请求带回，历史会话存 localStorage，切换会话不丢。
- **统一网关** `LlmGateway`：三家用同构接口，新增厂商只需在 `ai.providers` 下加一段配置。
- **降级策略**：没有 Key、厂商报错、网络超时，都会推送一条「规则兜底」消息，界面不白屏。

### 1. 对话式计划生成（`/api/plan/chat`）

```
用户自然语言（"帮我生成一周减脂计划，只有哑铃，膝盖不好"）
        ↓ 规则解析意图：目标/天数/时长/器械/禁忌部位（AI 挂了也能用）
GeneratePlanRequest
        ↓ PlanService：按难度+器械+禁忌症从动作库筛候选
大模型只做编排，输出结构化 JSON（训练分化、组次、RPE）
        ↓ 严格校验：exerciseId 必须在候选集内，否则丢弃
落库 plans + plan_details，返回完整 PlanVO
```

前端会把返回的计划渲染成卡片，并提示跳转到「训练计划」页查看每天安排。

### 2. 智能运动搭配（`/api/plan/match`）

按时间预算从动作库依次挑选热身 / 主项 / 辅助 / 核心 / 有氧 / 拉伸，每个动作带组次、休息、动作要领、MET、时长与热量估算；大模型只负责写 2-3 句编排说明，失败则用规则说明。动作全部来自数据库，不会编造。

### 3. 检索增强对话（`/api/ai/chat`）

普通聊天会先按问题里的肌群、器械、类别关键词检索动作库，注入候选清单并要求模型只引用库内动作；回复末尾解析出被引用的动作 ID，前端渲染成可跳转动作库的卡片。

**统一的可用性设计**：AI Key 未配置、模型返回非法 JSON、接口超时或不可用时，全部自动降级为内置规则引擎，功能仍然可用。`LlmGateway` 设置了 8s 连接 / 90s（流式 180s）读取超时，不会拖死请求。

### 4. 回答质量保障（避免「答非所问」）

大模型常见的问题是：用户问体脂率，它回一段通用的训练频率建议。系统从三个地方压制这种行为：

1. **指标预计算**：`util/BodyMetrics` 先用档案里的身高/体重/年龄/性别/活动量算出 BMI、BMR（Mifflin-St Jeor）、TDEE、体脂率（Deurenberg 公式）、目标热量与蛋白质区间，再把结果作为「已算好的指标」注入 system prompt。模型只负责解释，不负责算术。
2. **意图分流**：`AiService.retrieveForChat` 识别到「体脂 / BMI / 热量 / 蛋白质 / 怎么算」这类计算题时**不注入动作库**。否则候选动作会把模型带偏成「给你排一套训练」——这正是答非所问的主要来源。
3. **回答纪律**：system prompt 明确要求「第一句正面回答」「计算题按公式→代入→结果→区间四步写」「禁止用『问题比较宽泛』回避」，并把动作库约束限定在「推荐动作」这一种场景，营养 / 恢复 / 体成分问题按通用运动科学回答。

模型不可用时，`ChatService.fallbackText` 对计算类问题直接输出后端算出的指标表，而不是「你可以试试我内置的能力」这类空话。

---

## 七、主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/register` / `/api/auth/login` | 注册 / 登录 |
| GET / PUT | `/api/data/profile` | 健康档案（含训练偏好） |
| GET | `/api/exercise/list` | 动作库列表（类别/肌群/器械/难度筛选） |
| GET | `/api/exercise/stats` | 动作库统计 |
| GET | `/api/exercise/{id}` | 动作详情 |
| GET | `/api/plan/current` | 当前生效计划 |
| POST | `/api/plan/generate` | 生成一周训练计划 |
| POST | `/api/plan/chat` | **对话式生成**（自然语言 → 计划 / 搭配） |
| POST | `/api/plan/match` | **智能运动搭配** |
| GET | `/api/plan/list` / `/api/plan/{id}` | 历史计划 / 详情 |
| DELETE | `/api/plan/{id}` | 删除计划 |
| GET | `/api/chat/models` | **可用模型清单**（厂商 / 角标 / 是否已配置 Key） |
| POST | `/api/chat/stream` | **流式对话**（SSE，逐字返回，可中止） |
| POST | `/api/chat` | 非流式对话（接口调试 / 降级） |
| POST | `/api/ai/chat` | 检索增强对话（兼容旧接口） |
| POST | `/api/ai/predict-weight` 等 | 体重预测、饮食/运动建议、每日总结 |
| GET | `/api/health` | 健康检查 |

统一响应体同时包含 `code`（200/400/500）、`success`、`message`、`data`；前端两种判断方式都兼容。

---

## 八、配置说明

`FitLife-java/src/main/resources/application.yml`：

```yaml
spring:
  profiles:
    default: local                      # 本地开发默认值在 application-local.yml
  datasource:
    username: ${DB_USER:root}
    password: ${DB_PASS:}               # P1：不再有内置口令，由 .env / 环境变量注入

jwt:
  secret: ${JWT_SECRET:}                # P1：必须 >=32 字节，缺失则启动即失败

ai:
  default-model: ${AI_DEFAULT_MODEL:deepseek-chat}   # 没有该模型的 Key 时自动回落
  rate-limit: 30
  providers:
    - id: deepseek
      api-url: https://api.deepseek.com/chat/completions
      api-key: ${DEEPSEEK_API_KEY:}
      models: [deepseek-chat, deepseek-reasoner]
    - id: doubao
      api-url: https://ark.cn-beijing.volces.com/api/v3/chat/completions
      api-key: ${ARK_API_KEY:}
      models: [doubao-seed-1-6-flash, doubao-seed-1-6]
    - id: glm
      api-url: https://open.bigmodel.cn/api/paas/v4/chat/completions
      api-key: ${ZHIPU_API_KEY:}        # P1：内置 Key 已移除，需自行配置
      models: [glm-4-flash, glm-4.6]

app:
  cors:
    allowed-origins: ${CORS_ORIGINS:*}  # 部署时收窄为前端域名
```

> P1 起密钥一律走 `.env`（模板见 `.env.example`）：`start-fitlife.ps1` 与
> `scripts/p1-oneclick.ps1` 会自动生成随机 `JWT_SECRET` 并注入进程。

### 怎么开模型

| 厂商 | 领取地址 | 环境变量 | 免费额度 |
| --- | --- | --- | --- |
| DeepSeek | https://platform.deepseek.com/api_keys | `DEEPSEEK_API_KEY` | 注册赠送额度，V3.2 便宜、R1 推理强 |
| 豆包（火山方舟） | https://console.volcengine.com/ark | `ARK_API_KEY` | 每个模型 50 万 token，Flash 极速 |
| 智谱 GLM | https://open.bigmodel.cn/usercenter/apikeys | `ZHIPU_API_KEY` | `glm-4-flash` 免费，仓库已内置 |

三种方式任选其一填 Key 即可，**不改一行 Java 代码**：

1. `.env` 文件（推荐）：复制 `.env.example` 为 `.env`，填入 `DEEPSEEK_API_KEY=sk-xxx`，一键脚本会自动加载；
2. 环境变量（Windows 下 `setx DEEPSEEK_API_KEY sk-xxx` 后重开终端）；
3. 豆包若提示需要「推理接入点」，把 `models[].id` 换成控制台里的 `ep-xxxxxxxx`。

> P1 起不再支持把 Key 直接写进 `application.yml`——配置文件只保留 `${ENV:}` 占位符，
> 避免「源码一公开，Key 一起公开」。

未配置的厂商会在对话页模型列表里置灰，鼠标移上去能看到缺哪个环境变量。

---

## 八·五、P1 数据与安全硬化（本次改造）

对应优化方案 P1 阶段，详见 [docs/P1-改造说明.md](docs/P1-改造说明.md)。

| 项 | 一句话说明 | 关键文件 |
| --- | --- | --- |
| 唯一约束与索引 | 当日记录 `(user_id,date)` 唯一，明细表补联合索引 | `sql/migration/V1__p1_hardening.sql` |
| 幂等键 | 写接口带 `Idempotency-Key`，重复提交回放首次响应 | `aspect/IdempotencyAspect.java` |
| RBAC 与归属 | JWT 携带 `role`，默认全站需登录，`/api/admin/**` 仅管理员 | `config/SecurityConfig.java` |
| 密钥外置 | `JWT_SECRET`/`DB_PASS`/`ZHIPU_API_KEY` 全部走环境变量 | `application.yml` + `application-local.yml` |
| 统一响应体 | 失败一律 `{code,success,message,data}`，500 只回追踪号 | `controller/GlobalExceptionHandler.java` |

新增接口：

- `GET /api/admin/users?keyword=&page=&size=`、`GET /api/admin/audit-logs`
- `PATCH /api/admin/users/{id}/status?status=ACTIVE|DISABLED`
- `PATCH /api/admin/users/{id}/role?role=USER|ADMIN`

新增表：`idempotency_key`、`audit_log`；`users` 增加 `role`/`status`/`last_login_at`。

一键执行与验收：

```powershell
pwsh -File scripts\p1-oneclick.ps1     # 迁移 → 构建 → 启动 → 自检
pwsh -File scripts\verify-p1.ps1       # 仅验收（服务已启动）
```

演示账号 `dandan / 123456` 已是管理员，可直接访问后台接口。

---

## 八·六、P2 AI 计划生成主链路（本次改造）

对应优化方案 P2 阶段，详见 [docs/P2-改造说明.md](docs/P2-改造说明.md)。

| 项 | 一句话说明 | 关键文件 |
| --- | --- | --- |
| 知识库与规则入库 | 24 条运动处方知识 + 6 条阈值规则入库，检索打分注入 Prompt 并快照落库 | `sql/migration/V2__p2_ai_pipeline.sql`、`service/KnowledgeRetriever.java` |
| Prompt 模板版本化 | PLAN_GEN v1 入库，`{{metrics}}/{{constraints}}` 占位，DB 缺失自动回退内置 | `service/PromptTemplateService.java` |
| 规则校验 + 最小修复 | 六类校验（靶心率/FITT/循序渐进/恢复/禁忌/容量），违例自动最小修复 ≤2 轮，报告随计划返回 | `service/PlanValidator.java`、`PlanRepairer.java` |
| LLM 可靠性 | 重试 2 次 / 跨厂商转移 / 熔断 / 30s 超时 / token 计量落 `ai_call_log` | `service/LlmGateway.java`（completeGuarded，旧路径不动） |
| 计划版本链 | `version/parent_id/change_note/validation_report/source_link`，可回滚任意历史版本 | `service/PlanOrchestrator.java`、`POST /api/plan/{id}/activate` |

新增接口（均只读，管理员）：`/api/admin/knowledge-items`、`/knowledge-rules`、
`/prompt-templates/{code}`、`/ai-calls`；用户侧新增 `POST /api/plan/{id}/activate`。
前端计划页显示版本徽标 v{n}、来源（大模型/已修复/模板）、校验报告面板与回滚按钮。

一键执行与验收：

```powershell
pwsh -File scripts\p2-oneclick.ps1     # 迁移 V1+V2 → 构建(含22项单测) → 启动 → P2自检 + P1回归
pwsh -File scripts\verify-p2.ps1       # 仅验收（服务已启动）
```

无 API Key 时主链路自动走模板降级（`source_link=TEMPLATE`），校验报告与版本链照常生成。

---

## 八·七、P3 多轮对话与局部修订（本次改造）

对应优化方案 P3 阶段，详见 [docs/P3-改造说明.md](docs/P3-改造说明.md)。

| 项 | 一句话说明 | 关键文件 |
| --- | --- | --- |
| 会话/消息持久化 | chat_session + chat_message，对话与修订全留服务端，跨设备可查 | `sql/migration/V3__p3_chat_revision.sql`、`service/ChatSessionService.java` |
| 上下文装配 | 档案 + 当前计划 + 校验结论 + 最近 6 轮 → 路由/生成共用 | `service/ChatSessionService.assembleContext` |
| 意图路由 | LLM 结构化 JSON 优先，无 Key 退关键词规则（换/强度/去掉/为什么） | `service/IntentRouter.java` |
| 局部修订 | 定位动作 → 候选替代 → 重算 → 校验 → v+1 落库 → 结构化 Diff | `service/PlanRevisionService.java`、`POST /api/plan/revise` |
| 幂等与回滚 | revise 端点幂等回放（Idempotent-Replay: true）；任意版本可 activate 回滚 | P1 幂等切面 + P2 activate |

AI 伙伴页对话同步落库；计划页新增「对话式微调」卡片（输入框 + 快捷指令 + Diff 展示）。
无 API Key 时意图路由走关键词规则层，修订链路全部在服务端完成，验收不依赖大模型。

一键执行与验收：

```powershell
pwsh -File scripts\p3-oneclick.ps1     # 迁移 V1+V2+V3 → 构建(含38项单测) → 启动 → P3自检 + P1/P2回归
pwsh -File scripts\verify-p3.ps1       # 仅验收（服务已启动）
```

---

## 八·八、P4 执行闭环与统计（本次改造）

对应优化方案 P4 阶段，详见 [docs/P4-改造说明.md](docs/P4-改造说明.md)。

| 项 | 一句话说明 | 关键文件 |
| --- | --- | --- |
| 打卡关联 | 打卡必须带 planDetailId，服务端校验明细归属并补齐动作名/计划日；分次完成按累加 | sql/migration/V4__p4_execution_loop.sql、service/CheckInService.java |
| 聚合接口 | 打卡同事务内重算日报与依从率快照，统计由服务端唯一口径提供 | service/DailySummaryService.java、service/StatsService.java |
| 前端改接 | Dashboard 图表改读 /api/data/stats/summary，目标热量对齐服务端 BodyMetrics | `views/Dashboard.vue`、	ypes/stats.ts |
| 风险分级 | DANGER/WARN/INFO 三档；今日训练卡附 Karvonen 靶心率与禁忌提示 | StatsService.buildRisks、service/TodayPlanService.java |
| 进阶闭环 | STOP>DELOAD>SWAP>PROGRESS>HOLD；仅高依从放行一键进阶，进阶走 P2 主链路 | service/ProgressService.java、POST /api/data/stats/advance |

新增接口：GET /api/data/today-plan、POST|DELETE /api/data/check-in、GET /api/data/stats/summary、GET /api/data/stats/advice、POST /api/data/stats/advance。
既有 /api/data/exercise-burn 仍为自由记录（不计入依从率），行为未变。

一键执行与验证：

```powershell
pwsh -File scripts\p4-oneclick.ps1     # 迁移 V1~V4 → 构建(真跑56项单测) → 启动 → P4自检 + P1/P2/P3 回归
pwsh -File scripts\verify-p4.ps1       # 单独验收，共 24 项
```

---

## 九、常见问题

**Q：启动后动作库还是 0 条？**
A：先看后端日志里 `动作库自动补齐` 的提示。若连不上 MySQL，请先启动 MySQL 服务并确认 `-DbPass` 与 `application.yml` 一致；能连上就会自动灌入 100 条。

**Q：AI 提示「服务暂不可用」？**
A：先看对话页模型选择器——被置灰的模型表示该厂商没填 Key。填任意一个 Key 即可恢复；即使不填，计划生成与运动搭配也会自动降级为规则模板，功能依然完整。（P1 起不再内置 GLM Key，请在 `.env` 里配置 `ZHIPU_API_KEY`。）

**Q：豆包报「model not found」？**
A：火山方舟部分账号需要用「推理接入点」，把 `application.yml` 里 `doubao` 的 model id 换成控制台的 `ep-xxxxxxxx` 即可。

**Q：一键脚本中文乱码？**
A：`start-fitlife.ps1` 已存为 UTF-8 BOM，PowerShell 5.1 可正确读取；`.bat` 只含 ASCII 文本并切到 65001 代码页。

**Q：导入数据库中文乱码？**
A：脚本已带 `--default-character-set=utf8mb4`，库也是 utf8mb4。手动导入时记得加同样参数。

**Q：如何扩充动作库？**
A：往 `exercises` 表插入记录即可（建议填 `contraindication`）。同步更新 `db/exercises.json` 可让新环境自动带上。

---

## 十、免责声明

本系统生成的训练与饮食建议仅供一般健康人群参考，不构成医疗建议。如有伤病、慢性疾病或特殊情况，请遵医嘱并在专业人士指导下训练。
