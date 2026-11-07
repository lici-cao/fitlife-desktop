# 🏋️ FitLife Spring Boot API 接口文档

---

## 📋 基础信息

| 项目 | 说明 |
|------|------|
| 后端框架 | Spring Boot 3.2.x |
| 服务地址 | `http://localhost:8080` |
| 认证方式 | JWT Token (Bearer Token) |
| 请求格式 | JSON |
| 响应格式 | JSON |
| 数据库 | MySQL 8（utf8mb4，含 exercises 动作库 / plans / plan_details） |
| AI 服务 | 多模型：DeepSeek / 豆包（火山方舟）/ 智谱 GLM，统一走 OpenAI 兼容 `/chat/completions`，默认 `deepseek-chat` |

---

## 🏗️ 技术栈

| 技术 | 说明 |
|------|------|
| Spring Boot 3.2 | 核心 Web 框架 |
| Spring Security | 安全与权限控制 |
| Spring Data JPA | 数据持久化 |
| JWT (jjwt 0.12.3) | 身份认证令牌 |
| Lombok | 简化 Java 代码 |
| MySQL 8 | 关系型数据库 |
| HttpURLConnection + SseEmitter | 调用 AI 服务并做流式输出（`LlmGateway`） |

---

## 📁 推荐项目结构

```
src/main/java/com/fitlife/
├── FitLifeApplication.java           # 启动类
├── config/
│   ├── SecurityConfig.java           # Security 配置
│   ├── JwtConfig.java                # JWT 配置
│   └── CorsConfig.java               # 跨域配置
├── controller/
│   ├── AuthController.java           # 认证控制器
│   ├── DataController.java           # 数据控制器
│   └── AiController.java             # AI 智能控制器
├── service/
│   ├── AuthService.java              # 认证服务
│   ├── DataService.java              # 数据服务
│   ├── JwtService.java               # JWT 服务
│   └── AiService.java                # AI 服务
├── entity/
│   ├── User.java                     # 用户实体
│   ├── UserProfile.java              # 用户档案实体
│   ├── DailyRecord.java              # 每日记录实体
│   ├── CustomFood.java               # 自定义食物实体
│   └── WorkoutRecord.java            # 运动记录实体
├── repository/
│   ├── UserRepository.java
│   ├── UserProfileRepository.java
│   ├── DailyRecordRepository.java
│   ├── CustomFoodRepository.java
│   └── WorkoutRecordRepository.java
├── dto/
│   ├── request/
│   │   ├── LoginRequest.java
│   │   ├── RegisterRequest.java
│   │   ├── ProfileDTO.java
│   │   ├── FoodIntakeDTO.java
│   │   ├── ExerciseBurnDTO.java
│   │   ├── CustomFoodDTO.java
│   │   └── ChatRequest.java
│   └── ApiResponse.java              # 统一响应
└── filter/
    └── JwtAuthenticationFilter.java  # JWT 认证过滤器
```

---

## 📡 统一响应格式

所有接口统一返回以下格式：

```json
{
  "success": true,
  "message": "操作成功",
  "data": {}
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 操作是否成功 |
| message | string | 操作说明或错误信息 |
| data | object/array | 返回数据（可为 null） |

---

## 🔐 API 接口列表

### 1️⃣ 认证接口（公开）

#### 1.1 用户注册

```
POST /api/auth/register
Content-Type: application/json
```

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| username | string | 是 | 用户名，4-20 位字符 |
| password | string | 是 | 密码，至少 6 位 |

```json
{
  "username": "user1",
  "password": "password123"
}
```

**成功响应**（HTTP 200）

```json
{
  "success": true,
  "message": "注册成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "user": {
      "id": 1,
      "username": "user1"
    }
  }
}
```

**失败响应**

```json
{
  "success": false,
  "message": "用户名已存在"
}
```

---

#### 1.2 用户登录

```
POST /api/auth/login
Content-Type: application/json
```

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| username | string | 是 | 用户名 |
| password | string | 是 | 密码 |

```json
{
  "username": "user1",
  "password": "password123"
}
```

**成功响应**

```json
{
  "success": true,
  "message": "登录成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "user": {
      "id": 1,
      "username": "user1"
    }
  }
}
```

**失败响应**

```json
{
  "success": false,
  "message": "用户名或密码错误"
}
```

---

### 2️⃣ 用户档案接口（需认证）

> 🔒 **所有以下接口需在请求头中携带 Token：**
> ```
> Authorization: Bearer <your-jwt-token>
> ```

#### 2.1 获取用户档案

```
GET /api/data/profile
Authorization: Bearer <token>
```

**成功响应**

```json
{
  "success": true,
  "data": {
    "name": "张三",
    "gender": "female",
    "age": 25,
    "height": 165,
    "weight": 60,
    "bodyFat": 25,
    "goal": "fat_loss",
    "activityLevel": "moderate"
  }
}
```

**字段说明**

| 字段 | 类型 | 可选值 |
|------|------|--------|
| gender | string | `male` / `female` |
| goal | string | `fat_loss` / `maintain` / `muscle_gain` |
| activityLevel | string | `sedentary` / `light` / `moderate` / `active` / `very_active` |

---

#### 2.2 更新用户档案

```
PUT /api/data/profile
Authorization: Bearer <token>
Content-Type: application/json
```

**请求体**

```json
{
  "name": "张三",
  "gender": "female",
  "age": 25,
  "height": 165,
  "weight": 58,
  "bodyFat": 24,
  "goal": "fat_loss",
  "activityLevel": "moderate"
}
```

**成功响应**

```json
{
  "success": true,
  "message": "档案更新成功"
}
```

---

### 3️⃣ 每日记录接口（需认证）

#### 3.1 获取今日记录

```
GET /api/data/daily-record/today
Authorization: Bearer <token>
```

**成功响应**

```json
{
  "success": true,
  "data": {
    "date": "2024-06-09",
    "foodIntake": 1200,
    "exerciseBurn": 300,
    "targetCalories": 1800,
    "weight": 60
  }
}
```

---

#### 3.2 获取每日记录列表

```
GET /api/data/daily-records?startDate=2024-06-01&endDate=2024-06-09
Authorization: Bearer <token>
```

**查询参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| startDate | string | 否 | 开始日期 (YYYY-MM-DD) |
| endDate | string | 否 | 结束日期 (YYYY-MM-DD) |

**成功响应**

```json
{
  "success": true,
  "data": [
    {
      "date": "2024-06-09",
      "foodIntake": 1200,
      "exerciseBurn": 300,
      "targetCalories": 1800,
      "weight": 60
    },
    {
      "date": "2024-06-08",
      "foodIntake": 1650,
      "exerciseBurn": 500,
      "targetCalories": 1800,
      "weight": 60.2
    }
  ]
}
```

---

### 4️⃣ 食物摄入接口（需认证）

#### 4.1 添加食物摄入

```
POST /api/data/food-intake
Authorization: Bearer <token>
Content-Type: application/json
```

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| calories | number | 是 | 摄入热量（大卡） |
| foodId | string | 否 | 食物 ID |
| amount | number | 否 | 食物份量（克） |

```json
{
  "calories": 350,
  "foodId": "1",
  "amount": 100
}
```

**成功响应**

```json
{
  "success": true,
  "message": "食物摄入已添加",
  "data": {
    "todayFoodIntake": 1550
  }
}
```

---

### 5️⃣ 运动消耗接口（需认证）

#### 5.1 添加运动消耗

```
POST /api/data/exercise-burn
Authorization: Bearer <token>
Content-Type: application/json
```

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| calories | number | 是 | 消耗热量（大卡） |
| exerciseId | string | 是 | 运动项目 ID |
| exerciseName | string | 否 | 运动项目名称 |
| duration | number | 是 | 运动时长（分钟） |

```json
{
  "calories": 300,
  "exerciseId": "1",
  "exerciseName": "跑步",
  "duration": 30
}
```

**成功响应**

```json
{
  "success": true,
  "message": "运动记录已添加"
}
```

---

#### 5.2 获取运动记录列表

```
GET /api/data/workout-records?startDate=2024-06-01&endDate=2024-06-09
Authorization: Bearer <token>
```

**成功响应**

```json
{
  "success": true,
  "data": [
    {
      "date": "2024-06-09",
      "exerciseId": "1",
      "exerciseName": "跑步",
      "duration": 30,
      "caloriesBurned": 300
    }
  ]
}
```

---

### 6️⃣ 自定义食物接口（需认证）

#### 6.1 获取自定义食物列表

```
GET /api/data/custom-foods
Authorization: Bearer <token>
```

**成功响应**

```json
{
  "success": true,
  "data": [
    {
      "id": "1",
      "name": "鸡胸肉沙拉",
      "calories": 250,
      "protein": 30,
      "carbs": 10,
      "fat": 8,
      "servingSize": 200,
      "unit": "克",
      "category": "common"
    }
  ]
}
```

---

#### 6.2 添加自定义食物

```
POST /api/data/custom-foods
Authorization: Bearer <token>
Content-Type: application/json
```

**请求体**

```json
{
  "name": "鸡胸肉沙拉",
  "calories": 250,
  "protein": 30,
  "carbs": 10,
  "fat": 8,
  "servingSize": 200,
  "unit": "克",
  "category": "common"
}
```

**成功响应**

```json
{
  "success": true,
  "message": "自定义食物已添加",
  "data": {
    "id": "1",
    "name": "鸡胸肉沙拉",
    "calories": 250,
    ...
  }
}
```

---

#### 6.3 删除自定义食物

```
DELETE /api/data/custom-foods/{id}
Authorization: Bearer <token>
```

**成功响应**

```json
{
  "success": true,
  "message": "自定义食物已删除"
}
```

---

### 7️⃣ 数据同步接口（需认证）

#### 7.1 同步所有数据

```
GET /api/data/sync
Authorization: Bearer <token>
```

**说明**：首次登录时调用，一次性获取用户所有数据。

**成功响应**

```json
{
  "success": true,
  "data": {
    "profile": {
      "name": "张三",
      "gender": "female",
      "age": 25,
      ...
    },
    "todayRecord": {
      "date": "2024-06-09",
      "foodIntake": 1200,
      "exerciseBurn": 300,
      ...
    },
    "customFoods": [ ... ],
    "workoutRecords": [ ... ],
    "dailyRecords": [ ... ]
  }
}
```

---

### 8️⃣ AI 智能接口（需认证）

> 🤖 **AI 服务说明**：后端需对接国内 AI 服务（如：字节豆包 / 智谱 GLM / 阿里通义千问 等）。
> 建议使用官方提供的 SDK 或直接调用 HTTP API。

#### 8.1 智能聊天对话

```
POST /api/ai/chat
Authorization: Bearer <token>
Content-Type: application/json
```

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| message | string | 是 | 用户发送的消息 |
| context | object | 否 | 用户上下文数据（档案 + 今日数据） |

```json
{
  "message": "我今天吃了 1800 大卡，是不是太多了？",
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
      "foodIntake": 1800,
      "exerciseBurn": 200,
      "targetCalories": 1686
    },
    "bmr": 1410,
    "tdee": 2186,
    "targetCalories": 1686
  }
}
```

**成功响应**

```json
{
  "success": true,
  "data": {
    "reply": "根据你的目标（减脂）和今日数据：\n\n**摄入：** 1800 大卡\n**消耗：** 200 大卡\n**目标：** 1686 大卡\n\n今天热量摄入略高于目标，建议：\n\n1. 晚餐选择高蛋白低热量食物（如鸡胸肉、鱼）\n2. 增加 20 分钟有氧运动\n3. 多喝水，控制饱腹感\n\n整体仍在健康范围内，继续加油！💪"
  }
}
```

---

#### 8.2 体重变化预测

```
POST /api/ai/predict-weight
Authorization: Bearer <token>
Content-Type: application/json
```

**请求体**

```json
{
  "currentWeight": 60,
  "targetWeight": 55,
  "calorieDeficit": 500,
  "activityLevel": "moderate",
  "days": 30
}
```

**字段说明**

| 字段 | 类型 | 说明 |
|------|------|------|
| currentWeight | number | 当前体重（kg） |
| targetWeight | number | 目标体重（kg） |
| calorieDeficit | number | 每日热量缺口（大卡） |
| activityLevel | string | 活动水平 |
| days | number | 预测天数 |

**成功响应**

```json
{
  "success": true,
  "data": {
    "predictedWeight": 58,
    "prediction": "按当前习惯，30 天后预计体重约 58 kg，减少约 2 kg。",
    "trend": "decreasing",
    "weeklyLoss": 0.46,
    "advice": "每周减重 0.46 kg，处于健康范围（0.5-1 kg/周），建议保持！"
  }
}
```

---

#### 8.3 饮食建议

```
POST /api/ai/diet-suggestion
Authorization: Bearer <token>
Content-Type: application/json
```

**请求体**

```json
{
  "foodIntake": 1200,
  "targetCalories": 1686,
  "goal": "fat_loss",
  "protein": 60,
  "carbs": 120,
  "fat": 40
}
```

**成功响应**

```json
{
  "success": true,
  "data": {
    "summary": "当前摄入偏低，需增加蛋白质和健康脂肪",
    "advice": [
      "建议添加一份鸡胸肉（约 25g 蛋白）",
      "可以增加 10 颗杏仁补充健康脂肪",
      "主食选择燕麦或糙米，增加饱腹感"
    ],
    "calorieGap": 486,
    "proteinGap": 30
  }
}
```

---

#### 8.4 运动建议

```
POST /api/ai/exercise-suggestion
Authorization: Bearer <token>
Content-Type: application/json
```

**请求体**

```json
{
  "exerciseBurn": 0,
  "targetCalories": 1686,
  "activityLevel": "moderate",
  "goal": "fat_loss"
}
```

**成功响应**

```json
{
  "success": true,
  "data": {
    "summary": "今日尚未运动，建议进行 30 分钟有氧运动",
    "suggestions": [
      "快走 45 分钟（约消耗 180 大卡）",
      "慢跑 30 分钟（约消耗 300 大卡）",
      "居家 HIIT 20 分钟（约消耗 250 大卡）"
    ],
    "targetBurn": 300
  }
}
```

---

#### 8.5 每日总结

```
POST /api/ai/daily-summary
Authorization: Bearer <token>
Content-Type: application/json
```

**请求体**

```json
{
  "foodIntake": 1850,
  "exerciseBurn": 300,
  "targetCalories": 1686,
  "weight": 60,
  "goal": "fat_loss"
}
```

**成功响应**

```json
{
  "success": true,
  "data": {
    "summary": "今日摄入偏高，但运动消耗表现良好",
    "score": 75,
    "highlights": [
      "✅ 运动达标：消耗 300 大卡",
      "⚠️ 热量超标：超出目标 164 大卡",
      "💡 建议：明天控制晚餐份量"
    ]
  }
}
```

---

### 9️⃣ 健康检查（公开）

```
GET /api/health
```

**成功响应**

```json
{
  "status": "ok",
  "message": "FitLife Spring Boot Server is running"
}
```

---

## ❌ HTTP 状态码说明

| 状态码 | 说明 |
|--------|------|
| 200 | 请求成功 |
| 201 | 创建成功 |
| 400 | 请求参数错误 |
| 401 | 未登录 / Token 无效 |
| 403 | Token 过期 / 无权限 |
| 404 | 资源不存在 |
| 500 | 服务器内部错误 |

---

## 🗄️ 数据库表结构

### users 表

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT | 用户 ID |
| username | VARCHAR(50) | UNIQUE, NOT NULL | 用户名 |
| password | VARCHAR(255) | NOT NULL | 加密后的密码 |
| created_at | DATETIME | | 创建时间 |

### user_profiles 表

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGINT | PK | 档案 ID |
| user_id | BIGINT | FK, UNIQUE | 用户 ID |
| name | VARCHAR(50) | | 姓名 |
| gender | VARCHAR(10) | | 性别 |
| age | INT | | 年龄 |
| height | DOUBLE | | 身高（cm） |
| weight | DOUBLE | | 体重（kg） |
| body_fat | DOUBLE | | 体脂率（%） |
| goal | VARCHAR(20) | | 目标 |
| activity_level | VARCHAR(20) | | 活动水平 |

### daily_records 表

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGINT | PK | 记录 ID |
| user_id | BIGINT | FK | 用户 ID |
| date | VARCHAR(10) | UNIQUE(user_id, date) | 日期 |
| food_intake | DOUBLE | | 食物摄入（大卡） |
| exercise_burn | DOUBLE | | 运动消耗（大卡） |
| target_calories | DOUBLE | | 目标热量 |
| weight | DOUBLE | | 当日体重 |

### custom_foods 表

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | VARCHAR | PK | 食物 ID |
| user_id | BIGINT | FK | 用户 ID |
| name | VARCHAR(100) | | 食物名称 |
| calories | DOUBLE | | 热量（大卡/基础份量） |
| protein | DOUBLE | | 蛋白质（g） |
| carbs | DOUBLE | | 碳水化合物（g） |
| fat | DOUBLE | | 脂肪（g） |
| serving_size | DOUBLE | | 基础份量 |
| unit | VARCHAR(20) | | 单位 |
| category | VARCHAR(50) | | 分类 |

### workout_records 表

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| id | BIGINT | PK | 记录 ID |
| user_id | BIGINT | FK | 用户 ID |
| date | VARCHAR(10) | | 日期 |
| exercise_id | VARCHAR(50) | | 运动 ID |
| exercise_name | VARCHAR(100) | | 运动名称 |
| duration | INT | | 时长（分钟） |
| calories_burned | DOUBLE | | 消耗热量 |

---

## 🔧 Spring Boot 实现示例

### application.yml 配置

```yaml
server:
  port: 8080

spring:
  datasource:
    driver-class-name: org.sqlite.JDBC
    url: jdbc:sqlite:fitlife.db
  jpa:
    database-platform: org.hibernate.community.dialect.SQLiteDialect
    hibernate:
      ddl-auto: update
    show-sql: false

jwt:
  secret: your-secret-key-at-least-256-bits-long-xxxxxxxxxxxxxxxx
  expiration: 604800000  # 7 天

# AI 服务配置（示例 - 豆包/智谱）
ai:
  provider: doubao  # doubao / zhipu / qwen
  api-key: your-ai-api-key
  model: doubao-1.5-pro
  endpoint: https://ark.cn-beijing.volces.com/api/v3
  timeout: 30000
```

### SecurityConfig 示例

```java
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**", "/api/health").permitAll()
                .requestMatchers("/api/data/**", "/api/ai/**").authenticated()
                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

### AiController 示例

```java
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse> chat(@RequestBody ChatRequest request, Authentication auth) {
        Long userId = ((User) auth.getPrincipal()).getId();
        String reply = aiService.chat(request.getMessage(), request.getContext(), userId);
        return ResponseEntity.ok(ApiResponse.success(null, Map.of("reply", reply)));
    }

    @PostMapping("/predict-weight")
    public ResponseEntity<ApiResponse> predictWeight(
            @RequestBody WeightPredictionRequest request,
            Authentication auth) {
        Long userId = ((User) auth.getPrincipal()).getId();
        Map<String, Object> result = aiService.predictWeight(request, userId);
        return ResponseEntity.ok(ApiResponse.success(null, result));
    }

    @PostMapping("/diet-suggestion")
    public ResponseEntity<ApiResponse> dietSuggestion(
            @RequestBody DietSuggestionRequest request,
            Authentication auth) {
        Long userId = ((User) auth.getPrincipal()).getId();
        Map<String, Object> result = aiService.getDietSuggestion(request, userId);
        return ResponseEntity.ok(ApiResponse.success(null, result));
    }

    @PostMapping("/exercise-suggestion")
    public ResponseEntity<ApiResponse> exerciseSuggestion(
            @RequestBody ExerciseSuggestionRequest request,
            Authentication auth) {
        Long userId = ((User) auth.getPrincipal()).getId();
        Map<String, Object> result = aiService.getExerciseSuggestion(request, userId);
        return ResponseEntity.ok(ApiResponse.success(null, result));
    }

    @PostMapping("/daily-summary")
    public ResponseEntity<ApiResponse> dailySummary(
            @RequestBody DailySummaryRequest request,
            Authentication auth) {
        Long userId = ((User) auth.getPrincipal()).getId();
        Map<String, Object> result = aiService.getDailySummary(request, userId);
        return ResponseEntity.ok(ApiResponse.success(null, result));
    }
}
```

### AiService 示例（对接国内 AI）

```java
@Service
public class AiService {

    @Value("${ai.endpoint}")
    private String aiEndpoint;

    @Value("${ai.api-key}")
    private String apiKey;

    @Value("${ai.model}")
    private String model;

    private final RestTemplate restTemplate;

    public String chat(String message, Map<String, Object> context, Long userId) {
        // 构建 Prompt
        String systemPrompt = buildSystemPrompt(context);

        // 调用 AI API（示例 - 兼容 OpenAI 格式的国内服务）
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", message)
        ),
        body.put("temperature", 0.7);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                aiEndpoint + "/chat/completions",
                entity,
                String.class
            );
            // 解析返回内容，提取 AI 回复
            return extractReplyFromResponse(response.getBody());
        } catch (Exception e) {
            // 降级方案
            return fallbackReply(message, context);
        }
    }

    private String buildSystemPrompt(Map<String, Object> context) {
        return "你是一位专业的健身教练和营养师。用户信息如下：" +
               (context != null ? context.toString() : "") +
               "请根据用户信息提供专业、鼓励性的建议。回复请使用 Markdown 格式。";
    }
}
```

### JwtAuthenticationFilter 示例

```java
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        if (!jwtService.validateToken(token)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.getWriter().write("{\"success\":false,\"message\":\"Token 无效\"}");
            return;
        }

        String username = jwtService.extractUsername(token);
        User user = userRepository.findByUsername(username).orElse(null);
        if (user != null) {
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user, null, Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
```

---

## 🌐 前端对接参考

### API 服务层（src/api/ai.ts）

```typescript
import { http } from '../utils/http'

/**
 * AI 健身搭子服务
 */
export const aiApi = {
  chat(message: string, context?: Record<string, any>) {
    return http.post('/ai/chat', { message, context })
  },

  predictWeight(data: {
    currentWeight: number
    targetWeight: number
    calorieDeficit: number
    activityLevel: string
    days: number
  }) {
    return http.post('/ai/predict-weight', data)
  },

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

  getExerciseSuggestion(data: {
    exerciseBurn: number
    targetCalories: number
    activityLevel: string
    goal: string
  }) {
    return http.post('/ai/exercise-suggestion', data)
  },

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
```

### HTTP 工具类（src/utils/http.ts）

```typescript
import axios, { AxiosError } from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'

// 开发环境使用相对路径（由 Vite 代理转发）
// 生产环境可通过环境变量配置
const BASE_URL = import.meta.env.VITE_APP_API_URL || '/api'

const axiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: 30000,  // AI 接口响应较慢，设置 30 秒
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截器 - 自动添加 Token
axiosInstance.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem('fitlife_token')
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`
    }
    console.debug('[HTTP] Request:', config.baseURL + config.url)
    return config
  },
  (error: AxiosError) => Promise.reject(error)
)

// 响应拦截器 - 处理 Token 过期
axiosInstance.interceptors.response.use(
  (response: AxiosResponse) => response,
  (error: AxiosError) => {
    if (error.response?.status === 401 || error.response?.status === 403) {
      localStorage.removeItem('fitlife_token')
      localStorage.removeItem('fitlife_userId')
      window.location.href = '/login'
      return Promise.reject(new Error('Token 已过期，请重新登录'))
    }
    return Promise.reject(error)
  }
)

export const http = {
  get: (url: string, params?: any, options?: any) =>
    axiosInstance.get(url, { params, ...options }),
  post: (url: string, data?: any, options?: any) =>
    axiosInstance.post(url, data, options),
  put: (url: string, data?: any, options?: any) =>
    axiosInstance.put(url, data, options),
  delete: (url: string, options?: any) =>
    axiosInstance.delete(url, options)
}
```

### Vite 代理配置（vite.config.ts）

```typescript
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
```

---

## 🔌 推荐的国内 AI 服务

| 服务商 | 免费额度 | 系统内置模型 | 环境变量 | 文档地址 |
|--------|---------|---------|---------|---------|
| DeepSeek | 注册赠送额度 | `deepseek-chat` / `deepseek-reasoner` | `DEEPSEEK_API_KEY` | [DeepSeek 开放平台](https://platform.deepseek.com/api_keys) |
| 字节豆包 (Ark) | 每模型 50 万 token | `doubao-seed-1-6-flash` / `doubao-seed-1-6` | `ARK_API_KEY` | [火山方舟](https://console.volcengine.com/ark) |
| 智谱 GLM | `glm-4-flash` 免费 | `glm-4-flash` / `glm-4.6` | `ZHIPU_API_KEY` | [智谱 AI](https://open.bigmodel.cn/usercenter/apikeys) |

三家均兼容 OpenAI 格式，统一由 `LlmGateway` 调用，新增厂商只需在 `application.yml` 的 `ai.providers` 下追加配置。

### 对话相关接口

#### GET /api/chat/models — 可用模型清单

```bash
curl http://localhost:8080/api/chat/models -H "Authorization: Bearer <token>"
```

```json
{
  "code": 200,
  "success": true,
  "data": {
    "defaultModel": "deepseek-chat",
    "available": true,
    "models": [
      {
        "id": "deepseek-chat",
        "name": "DeepSeek V3.2",
        "providerId": "deepseek",
        "providerName": "DeepSeek",
        "tag": "免费额度",
        "free": true,
        "available": false,
        "isDefault": true,
        "note": "未配置 DEEPSEEK_API_KEY，暂时不可用"
      }
    ]
  }
}
```

`available=false` 表示该厂商没有配置 Key，前端会把对应模型置灰。

#### POST /api/chat/stream — 流式对话（SSE）

请求体：

```json
{
  "message": "帮我生成一周减脂计划，每周3天，每次45分钟",
  "model": "deepseek-chat",
  "sessionId": "m3k2a1",
  "history": [{ "role": "user", "content": "..." }, { "role": "assistant", "content": "..." }],
  "context": { "userProfile": { "weight": 60, "goal": "fat_loss" } },
  "allowAction": true
}
```

响应为 `text/event-stream`，每行一个事件：

```
data: {"type":"meta","model":"deepseek-chat","modelName":"DeepSeek V3.2","provider":"DeepSeek"}
data: {"type":"delta","text":"按你的档案"}
data: {"type":"delta","text":"我排了一个"}
data: {"type":"plan","plan":{ "id": 12, "title": "哑铃减脂 3 日分化计划" }}
data: {"type":"done","action":"PLAN_CREATED"}
```

| type | 说明 |
| --- | --- |
| `meta` | 本次使用的模型与厂商 |
| `delta` | 增量文本，累加即完整回复 |
| `plan` | 命中「生成计划」意图，返回已落库的计划 |
| `match` | 命中「运动搭配」意图，返回动作组合 |
| `exercises` | 回复里引用到的动作库动作 |
| `fallback` | 走了规则兜底，附 `reason` |
| `error` | 出错信息，`fatal=false` 表示仍可继续 |
| `done` | 结束标记 |

---

## ✅ 测试建议

### 1. 健康检查
```bash
curl http://localhost:8080/api/health
```

### 2. 注册用户
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"123456"}'
```

### 3. 登录获取 Token
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"123456"}'
```

### 4. 使用 Token 调用 AI 接口
```bash
curl -X POST http://localhost:8080/api/ai/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <your-token>" \
  -d '{"message":"我该怎么减脂？","context":{}}'
```

---

## 📄 前端项目文档

- 请查看前端项目根目录下的 [README.md](./README.md) 获取完整的项目介绍和使用说明

---

**FitLife - 让健康管理更简单 💪**
