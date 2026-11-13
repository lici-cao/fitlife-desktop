package com.fitlife.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.common.BusinessException;
import com.fitlife.config.AiModelProperties;
import com.fitlife.dto.*;
import com.fitlife.entity.Exercise;
import com.fitlife.repository.ExerciseRepository;
import com.fitlife.util.BodyMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final ExerciseRepository exerciseRepository;
    /** 统一模型网关：默认模型由 ai.default-model 决定，可在对话页按请求切换 */
    private final LlmGateway llmGateway;
    private final AiModelProperties modelProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern ACTION_ID_PATTERN =
            Pattern.compile("【动作ID】\\s*([0-9,，\\s]+)");

    // 简单的请求频率限制
    private final Map<String, List<Long>> requestTimestamps = new HashMap<>();

    public AiChatResponse chat(AiChatRequest request) {
        if (!checkRateLimit("chat")) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }

        try {
            // 检索增强：先从动作库取相关动作，再让模型只基于这些动作作答
            List<ExerciseDTO> candidates = retrieveExercises(request.getMessage(), request.getContext());
            String prompt = buildChatPrompt(request, candidates);
            String raw = callAiApi(prompt, request.getModel());

            AiChatResponse chatResponse = new AiChatResponse();
            List<ExerciseDTO> matched = extractReferencedExercises(raw, candidates);
            chatResponse.setReply(cleanReply(raw));
            chatResponse.setMatchedExercises(matched);
            chatResponse.setAction(inferAction(request.getMessage()));
            return chatResponse;
        } catch (Exception e) {
            log.error("AI chat error", e);
            throw new BusinessException(503, "AI 服务暂时不可用，请稍后重试或切换模型");
        }
    }

    public PredictWeightResponse predictWeight(PredictWeightRequest request) {
        if (!checkRateLimit("predict")) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }

        try {
            String prompt = buildPredictWeightPrompt(request);
            String response = callAiApi(prompt);

            PredictWeightResponse predictResponse = new PredictWeightResponse();
            predictResponse.setPrediction(response);
            predictResponse.setWeightTrend(generateWeightTrend(request.getCurrentWeight(), request.getTargetWeight()));
            return predictResponse;
        } catch (Exception e) {
            log.error("AI predict weight error", e);
            throw new BusinessException(503, "AI 服务暂时不可用，请稍后重试或切换模型");
        }
    }

    public DietSuggestionResponse dietSuggestion(DietSuggestionRequest request) {
        if (!checkRateLimit("diet")) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }

        try {
            String prompt = buildDietSuggestionPrompt(request);
            String response = callAiApi(prompt);

            DietSuggestionResponse dietResponse = new DietSuggestionResponse();
            dietResponse.setSummary(response);
            dietResponse.setRecommendations(parseRecommendations(response));
            return dietResponse;
        } catch (Exception e) {
            log.error("AI diet suggestion error", e);
            throw new BusinessException(503, "AI 服务暂时不可用，请稍后重试或切换模型");
        }
    }

    public ExerciseSuggestionResponse exerciseSuggestion(ExerciseSuggestionRequest request) {
        if (!checkRateLimit("exercise")) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }

        try {
            String prompt = buildExerciseSuggestionPrompt(request);
            String response = callAiApi(prompt);

            ExerciseSuggestionResponse exerciseResponse = new ExerciseSuggestionResponse();
            exerciseResponse.setSummary(response);
            exerciseResponse.setRecommendations(parseRecommendations(response));
            return exerciseResponse;
        } catch (Exception e) {
            log.error("AI exercise suggestion error", e);
            throw new BusinessException(503, "AI 服务暂时不可用，请稍后重试或切换模型");
        }
    }

    public DailySummaryResponse dailySummary(DailySummaryRequest request) {
        if (!checkRateLimit("summary")) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }

        try {
            String prompt = buildDailySummaryPrompt(request);
            String response = callAiApi(prompt);

            DailySummaryResponse summaryResponse = new DailySummaryResponse();
            summaryResponse.setSummary(response);
            summaryResponse.setAdvice(generateAdvice(request));
            return summaryResponse;
        } catch (Exception e) {
            log.error("AI daily summary error", e);
            throw new BusinessException(503, "AI 服务暂时不可用，请稍后重试或切换模型");
        }
    }

    public String chatZp(String userMessage) {
        try {
            String prompt = "你是一个友好的AI助手，用中文回答。" + userMessage;
            return callAiApi(prompt);
        } catch (Exception e) {
            log.error("调用智谱AI API失败", e);
            return "系统异常：" + e.getMessage();
        }
    }

    /**
     * 对外暴露的 AI 调用入口：供计划生成等模块按需指定温度与最大长度。
     * 使用配置里的默认模型（默认 DeepSeek）。
     */
    public String callAi(String prompt, double temperature, int maxTokens) throws Exception {
        return doCall(prompt, temperature, maxTokens, null);
    }

    /** 指定模型调用，modelId 为空时走默认模型 */
    public String callAi(String prompt, double temperature, int maxTokens, String modelId) {
        return doCall(prompt, temperature, maxTokens, modelId);
    }

    private String callAiApi(String prompt) {
        return doCall(prompt, 0.7, 1200, null);
    }

    private String callAiApi(String prompt, String modelId) {
        return doCall(prompt, 0.7, 1200, modelId);
    }

    private String doCall(String prompt, double temperature, int maxTokens, String modelId) {
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        messages.add(message);

        LlmGateway.Resolved target = llmGateway.resolve(modelId);
        log.debug("调用模型 {}/{}", target.providerName(), target.modelId());
        return llmGateway.complete(target, messages, temperature, maxTokens);
    }

    private boolean checkRateLimit(String endpoint) {
        long now = System.currentTimeMillis();
        List<Long> timestamps = requestTimestamps.computeIfAbsent(endpoint, k -> new ArrayList<>());

        timestamps.removeIf(timestamp -> now - timestamp > 60000);

        int limit = modelProperties.getRateLimit() == null ? 30 : modelProperties.getRateLimit();
        if (timestamps.size() >= limit) {
            return false;
        }

        timestamps.add(now);
        return true;
    }

    private String buildChatPrompt(AiChatRequest request, List<ExerciseDTO> candidates) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一名专业的健身教练，用中文简洁、专业地回答用户问题。\n");
        if (request.getContext() != null && !request.getContext().isEmpty()) {
            try {
                prompt.append("用户背景：").append(objectMapper.writeValueAsString(request.getContext())).append("\n");
            } catch (Exception e) {
                log.warn("Failed to serialize context", e);
            }
        }
        if (!candidates.isEmpty()) {
            prompt.append("\n【系统动作库】以下是本系统已审核的动作，涉及动作推荐时只能引用这些动作，禁止编造不存在的动作：\n");
            try {
                prompt.append(objectMapper.writeValueAsString(candidates)).append("\n");
            } catch (Exception e) {
                log.warn("Failed to serialize exercise candidates", e);
            }
            prompt.append("回答末尾请单独输出一行：\n【动作ID】<你实际推荐的动作 id，英文逗号分隔；若未推荐动作则留空>\n");
        }
        prompt.append("\n用户问题：").append(request.getMessage());
        return prompt.toString();
    }

    /** 检索增强：按问题里的肌群/器械/类别关键词，从动作库取候选动作 */
    private List<ExerciseDTO> retrieveExercises(String message, Map<String, Object> context) {
        String text = message == null ? "" : message;
        List<Exercise> all = exerciseRepository.findAll();
        if (all.isEmpty()) {
            return Collections.emptyList();
        }

        String muscle = matchKeyword(text, "胸", "背", "肩", "手臂", "腿", "臀", "核心", "腹", "全身");
        String equipment = matchKeyword(text, "哑铃", "杠铃", "弹力带", "壶铃", "徒手", "器械");
        String category = (text.contains("有氧") || text.contains("跑步") || text.contains("燃脂")) ? "有氧"
                : (text.contains("核心") || text.contains("腹肌")) ? "核心"
                : (text.contains("拉伸") || text.contains("柔韧")) ? "柔韧"
                : (text.contains("HIIT") || text.contains("间歇")) ? "HIIT"
                : (text.contains("力量") || text.contains("增肌")) ? "力量"
                : null;

        List<Exercise> picked = all.stream()
                .filter(e -> muscle == null || muscle.equals(e.getMuscleGroup())
                        || ("腹".equals(muscle) && "核心".equals(e.getMuscleGroup()))
                        || ("全身".equals(e.getMuscleGroup())))
                .filter(e -> equipment == null || equipment.equals(e.getEquipment()))
                .filter(e -> category == null || category.equals(e.getCategory()))
                .limit(15)
                .collect(java.util.stream.Collectors.toList());

        if (picked.isEmpty()) {
            picked = all.stream()
                    .filter(e -> e.getDifficulty() != null && e.getDifficulty() <= 2)
                    .limit(12)
                    .collect(java.util.stream.Collectors.toList());
        }
        return picked.stream().map(this::toDTO).collect(java.util.stream.Collectors.toList());
    }

    // ============================================================
    // 供对话页复用的公开入口（检索增强 + 提示词组装）
    // ============================================================

    public List<ExerciseDTO> retrieveForChat(String message, Map<String, Object> context) {
        if (message != null && BodyMetrics.CALC.matcher(message).find()) {
            return Collections.emptyList();
        }
        return retrieveExercises(message, context);
    }

    public List<ExerciseDTO> extractReferenced(String reply, List<ExerciseDTO> candidates) {
        return extractReferencedExercises(reply, candidates);
    }

    /**
     * 对话系统提示词：人设 + 用户画像 + 动作库约束 + 格式要求。
     * 这层约束就是论文里说的「知识库检索约束」，用来压制大模型编造动作。
     */
    public String buildSystemPrompt(Map<String, Object> context, List<ExerciseDTO> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 FitLife 的 AI 健身教练，用简体中文回答，风格专业、简洁、可执行。\n")
                .append("\n【回答纪律】\n")
                .append("1. 第一句必须正面回答用户问的那件事，不要先给通用铺垫。问体脂率就算体脂率，问热量就给热量，不要改答训练安排。\n")
                .append("2. 计算类问题（体脂率 / BMI / BMR / TDEE / 热量预算 / 蛋白质）按四步写：公式 → 代入用户真实数值 → 结果 → 判定区间。\n")
                .append("3. 禁止用「你的问题比较宽泛」「建议咨询专业人士」这类话回避；信息不足时先按用户档案给一版默认答案，再补一句什么信息能让它更准。\n")
                .append("4. 只有「推荐具体动作」时才受动作库限制；营养、恢复、体成分、心态等通用问题按运动科学常识正常回答，不确定的结论要明确标注不确定性。\n")
                .append("5. 输出用 Markdown 组织：先结论，再要点；可用小标题、列表、表格，不要输出 HTML 代码块。\n")
                .append("6. 用户提到伤病或疼痛时，先给安全提醒，再给替代动作；不做医疗诊断。\n");

        // 计算类问题不让模型硬算：后端先算好，模型只负责解释
        String metrics = BodyMetrics.describe(BodyMetrics.from(context));
        if (!metrics.isBlank()) {
            sb.append(metrics)
                    .append("回答涉及上面任一数值时，直接引用并说明依据；用户问「怎么算的」时给出公式与代入过程。\n");
        }

        if (context != null && !context.isEmpty()) {
            try {
                sb.append("\n【用户档案与今日数据】\n")
                        .append(objectMapper.writeValueAsString(context)).append("\n");
            } catch (Exception e) {
                log.warn("序列化对话上下文失败", e);
            }
        }

        if (candidates != null && !candidates.isEmpty()) {
            sb.append("\n【系统动作库】以下是本系统已审核的动作，推荐时必须从中选择：\n");
            try {
                sb.append(objectMapper.writeValueAsString(candidates)).append("\n");
            } catch (Exception e) {
                log.warn("序列化候选动作失败", e);
            }
            sb.append("回答末尾请单独输出一行：\n【动作ID】<你实际推荐的动作 id，英文逗号分隔；未推荐则留空>\n");
        }
        return sb.toString();
    }

    /** 从 AI 回复里解析被引用的动作，优先读【动作ID】行，兜底按动作名匹配 */
    private List<ExerciseDTO> extractReferencedExercises(String raw, List<ExerciseDTO> candidates) {
        if (raw == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> ids = new LinkedHashSet<>();
        Matcher m = ACTION_ID_PATTERN.matcher(raw);
        if (m.find()) {
            for (String part : m.group(1).split("[,，\\s]+")) {
                try {
                    if (!part.isBlank()) ids.add(Long.parseLong(part.trim()));
                } catch (NumberFormatException ignored) {
                    // 忽略非法 id
                }
            }
        }
        if (ids.isEmpty()) {
            for (ExerciseDTO dto : candidates) {
                if (dto.getName() != null && raw.contains(dto.getName())) {
                    ids.add(dto.getId());
                }
            }
        }
        return candidates.stream()
                .filter(d -> ids.contains(d.getId()))
                .limit(8)
                .collect(java.util.stream.Collectors.toList());
    }

    private String cleanReply(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("(?m)^【动作ID】.*$\\n?", "").trim();
    }

    private String inferAction(String message) {
        if (message == null) return null;
        if (message.contains("计划") || message.contains("课表")) return "GO_PLAN";
        if (message.contains("搭配") || message.contains("怎么练")) return "GO_MATCH";
        return null;
    }

    private String matchKeyword(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) return k;
        }
        return null;
    }

    private ExerciseDTO toDTO(Exercise e) {
        return ExerciseDTO.builder()
                .id(e.getId())
                .name(e.getName())
                .category(e.getCategory())
                .muscleGroup(e.getMuscleGroup())
                .equipment(e.getEquipment())
                .difficulty(e.getDifficulty())
                .metValue(e.getMetValue())
                .defaultSets(e.getDefaultSets())
                .defaultReps(e.getDefaultReps())
                .description(e.getDescription())
                .contraindication(e.getContraindication())
                .build();
    }

    private String buildPredictWeightPrompt(PredictWeightRequest request) {
        return String.format(
                "作为一个专业的健身教练，请分析以下体重数据：\n" +
                        "当前体重：%.1f kg\n" +
                        "目标体重：%.1f kg\n" +
                        "预测天数：%d 天\n\n" +
                        "请给出专业的体重预测分析和建议。",
                request.getCurrentWeight(),
                request.getTargetWeight(),
                request.getDays() != null ? request.getDays() : 30
        );
    }

    private String buildDietSuggestionPrompt(DietSuggestionRequest request) {
        return String.format(
                "作为一个专业的营养师，请根据以下信息给出饮食建议：\n" +
                        "当前体重：%.1f kg\n" +
                        "目标体重：%.1f kg\n" +
                        "健身目标：%s\n" +
                        "活动水平：%s\n\n" +
                        "请提供具体的饮食建议，包括每日热量摄入、营养分配和食物推荐。",
                request.getCurrentWeight() != null ? request.getCurrentWeight() : 0,
                request.getTargetWeight() != null ? request.getTargetWeight() : 0,
                request.getGoal() != null ? request.getGoal() : "保持健康",
                request.getActivityLevel() != null ? request.getActivityLevel() : "中等"
        );
    }

    private String buildExerciseSuggestionPrompt(ExerciseSuggestionRequest request) {
        return String.format(
                "作为一个专业的健身教练，请根据以下信息给出运动建议：\n" +
                        "健身目标：%s\n" +
                        "当前活动水平：%s\n" +
                        "可用时间：%d 分钟\n\n" +
                        "请提供具体的运动建议，包括运动类型、强度和频率。",
                request.getGoal() != null ? request.getGoal() : "保持健康",
                request.getActivityLevel() != null ? request.getActivityLevel() : "中等",
                request.getAvailableMinutes() != null ? request.getAvailableMinutes() : 30
        );
    }

    private String buildDailySummaryPrompt(DailySummaryRequest request) {
        return String.format(
                "请总结今天的健身数据：\n" +
                        "摄入热量：%.0f 千卡\n" +
                        "运动消耗：%.0f 千卡\n" +
                        "目标热量：%.0f 千卡\n" +
                        "当前体重：%.1f kg\n\n" +
                        "请给出专业的总结和建议。",
                request.getFoodIntake() != null ? request.getFoodIntake() : 0,
                request.getExerciseBurn() != null ? request.getExerciseBurn() : 0,
                request.getTargetCalories() != null ? request.getTargetCalories() : 0,
                request.getWeight() != null ? request.getWeight() : 0
        );
    }

    private List<Double> generateWeightTrend(double currentWeight, double targetWeight) {
        List<Double> trend = new ArrayList<>();
        double diff = targetWeight - currentWeight;
        double step = diff / 30.0;

        for (int i = 0; i <= 30; i++) {
            trend.add(Math.round((currentWeight + step * i) * 10.0) / 10.0);
        }

        return trend;
    }

    private List<String> parseRecommendations(String response) {
        List<String> recommendations = new ArrayList<>();
        String[] lines = response.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("•") || line.startsWith("-") || line.startsWith("*")) {
                recommendations.add(line.substring(1).trim());
            } else if (line.matches("\\d+\\..*")) {
                recommendations.add(line.replaceFirst("\\d+\\.", "").trim());
            }
        }

        if (recommendations.isEmpty()) {
            recommendations.add(response);
        }

        return recommendations;
    }

    private String generateAdvice(DailySummaryRequest request) {
        StringBuilder advice = new StringBuilder();

        double foodIntake = request.getFoodIntake() != null ? request.getFoodIntake() : 0;
        double exerciseBurn = request.getExerciseBurn() != null ? request.getExerciseBurn() : 0;
        double targetCalories = request.getTargetCalories() != null ? request.getTargetCalories() : 0;

        double netCalories = foodIntake - exerciseBurn;

        if (netCalories < targetCalories * 0.8) {
            advice.append("今日热量摄入偏低，建议适当增加营养摄入。");
        } else if (netCalories > targetCalories * 1.2) {
            advice.append("今日热量摄入偏高，建议适当控制饮食或增加运动量。");
        } else {
            advice.append("今日热量控制良好，继续保持！");
        }

        if (exerciseBurn < 100) {
            advice.append(" 运动量较少，建议增加一些有氧运动。");
        }

        return advice.toString();
    }
}
