package com.fitlife.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.service.LlmGateway.GuardedResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P3 意图路由（图 3 第 4 步）：GENERATE（生成新计划）/ REVISE（局部修订当前计划）/
 * QUERY（解释与溯源）/ CHAT（普通闲聊，回退旧链路）。
 *
 * 优先 LLM function-calling 风格（结构化 JSON 输出，走 completeGuarded 计量），
 * 无 Key / 解析失败 / 超时一律退关键词规则——保证离线环境整链路可用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRouter {

    public static final String GENERATE = "GENERATE";
    public static final String REVISE = "REVISE";
    public static final String QUERY = "QUERY";
    public static final String CHAT = "CHAT";

    /** 修订操作类型 */
    public static final String OP_REPLACE = "REPLACE";
    public static final String OP_ADJUST_RPE = "ADJUST_RPE";
    public static final String OP_REMOVE = "REMOVE";

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 路由结果。intent=REVISE 时 operation/dayOfWeek/exerciseName/newRpe 描述修订目标；
     * replaceHint 为"把 X 换成 Y"里的 Y（动作名或肌群提示）。
     */
    public record RouteResult(String intent, String operation, Integer dayOfWeek,
                               String exerciseName, Integer newRpe, String replaceHint,
                               boolean usedLlm, String note) {
        public static RouteResult of(String intent) {
            return new RouteResult(intent, null, null, null, null, null, false, null);
        }
    }

    private static final Pattern REPLACE_PATTERN =
            Pattern.compile("(?:把)?(.{1,12}?)(?:换(?:成|替|掉)|替换(?:成|为)?|改成)(.{1,12})");
    private static final Pattern NAME_IN_MSG =
            Pattern.compile("(?:去掉|删除|移除|别练|不要)(?:掉)?(.{1,12}?)(?:了|这个|那个|动作)?(?:，|。|$)");

    public RouteResult route(Long userId, String message, String contextSummary, boolean hasActivePlan) {
        if (message == null || message.isBlank()) return RouteResult.of(CHAT);
        String msg = message.trim();

        // ---------- 1) LLM 结构化路由（可失败，失败不抛） ----------
        RouteResult llm = routeByLlm(userId, msg, contextSummary, hasActivePlan);
        if (llm != null) return llm;

        // ---------- 2) 关键词规则兜底（离线可靠） ----------
        return routeByRules(msg, hasActivePlan);
    }

    private RouteResult routeByLlm(Long userId, String msg, String contextSummary, boolean hasActivePlan) {
        try {
            if (!llmGateway.hasAvailableModel()) return null;
            String prompt = """
                    你是健身计划系统的意图分类器。根据用户消息与上下文，只输出一个 JSON 对象，不要输出任何其他文字。

                    intent 取值（三选一，再加 CHAT）：
                    - GENERATE：用户想要一份新的完整训练计划（"重新生成/再来一份/给我排个计划"）
                    - REVISE：用户想修改现有计划中的个别动作或强度（"把X换成Y/去掉X/强度降一点"）
                    - QUERY：用户想了解计划的依据或解释（"为什么这么排/依据是什么"）
                    - CHAT：其他普通对话

                    REVISE 时补充字段：operation ∈ REPLACE|ADJUST_RPE|REMOVE；dayOfWeek（1-7，用户指定了哪天就填，没提就 null）；
                    exerciseName（要被替换/删除的动作名，没提到具体动作就 null）；newRpe（1-10）；replaceHint（想换成什么，动作名或肌群描述）。

                    输出格式：{"intent":"...","operation":"...","dayOfWeek":null,"exerciseName":"...","newRpe":null,"replaceHint":"..."}

                    上下文：
                    %s

                    用户消息：%s
                    """.formatted(contextSummary == null ? "（无）" : contextSummary, msg);
            GuardedResult r = llmGateway.completeGuarded(
                    List.of(Map.of("role", "user", "content", prompt)),
                    0.1, 300, null, "INTENT_ROUTE", userId);
            JsonNode node = objectMapper.readTree(PlanSupport.extractJson(r.content()));
            String intent = upper(node.path("intent").asText(""));
            if (intent.isEmpty()) return null;
            if (REVISE.equals(intent) && !hasActivePlan) {
                // 没有当前计划可改：升级为生成
                return new RouteResult(GENERATE, null, null, null, null, null, true, "无生效计划，改为生成");
            }
            if (!intent.equals(GENERATE) && !intent.equals(REVISE)
                    && !intent.equals(QUERY) && !intent.equals(CHAT)) {
                return null;
            }
            String operation = upper(node.path("operation").asText(""));
            if (operation.isEmpty() || (!operation.equals(OP_REPLACE) && !operation.equals(OP_ADJUST_RPE)
                    && !operation.equals(OP_REMOVE))) {
                operation = null;
            }
            Integer day = node.hasNonNull("dayOfWeek") ? node.path("dayOfWeek").asInt() : null;
            if (day != null && (day < 1 || day > 7)) day = null;
            Integer rpe = node.hasNonNull("newRpe") ? node.path("newRpe").asInt() : null;
            if (rpe != null && (rpe < 1 || rpe > 10)) rpe = null;
            return new RouteResult(intent, operation, day,
                    node.path("exerciseName").asText(null),
                    rpe, node.path("replaceHint").asText(null), true, null);
        } catch (Exception e) {
            log.debug("LLM 意图路由失败，退规则：{}", e.getMessage());
            return null;
        }
    }

    /** 离线规则路由：必须可靠，覆盖验收用例的表述。 */
    RouteResult routeByRules(String msg, boolean hasActivePlan) {
        // 生成意图
        if (msg.contains("重新生成") || msg.contains("再来一份") || msg.contains("新计划")
                || msg.contains("重新排") || (msg.contains("生成") && msg.contains("计划"))
                || msg.contains("排一") || msg.contains("给我一个计划")) {
            return RouteResult.of(GENERATE);
        }
        // 查询意图
        if (msg.contains("为什么") || msg.contains("依据") || msg.contains("原理")
                || msg.contains("怎么安排") || msg.contains("解释")) {
            return RouteResult.of(QUERY);
        }
        // 修订意图（需有当前计划）
        if (hasActivePlan) {
            Matcher m = REPLACE_PATTERN.matcher(msg);
            if (m.find()) {
                String from = clean(m.group(1));
                String to = clean(m.group(2));
                if (!from.isEmpty() && !to.isEmpty()) {
                    return new RouteResult(REVISE, OP_REPLACE, null, extractName(from),
                            null, to, false, null);
                }
            }
            if (msg.contains("换") && (msg.contains("动作") || msg.contains("练"))) {
                // 泛化的"换动作"：交修订服务自选最旧/最高强度目标
                return new RouteResult(REVISE, OP_REPLACE, null, null, null, null, false, "未指明目标，由系统挑选");
            }
            if (msg.contains("强度") || msg.contains("RPE") || msg.contains("rpe")
                    || msg.contains("太难") || msg.contains("太累") || msg.contains("轻松点")
                    || msg.contains("累")) {
                return new RouteResult(REVISE, OP_ADJUST_RPE, null, null, null, null, false,
                        msg.contains("加点") || msg.contains("提高") ? "提升强度" : "降低强度");
            }
            Matcher rm = NAME_IN_MSG.matcher(msg);
            if (msg.contains("去掉") || msg.contains("删除") || msg.contains("移除") || msg.contains("别练")) {
                if (rm.find()) {
                    String name = clean(rm.group(1));
                    if (!name.isEmpty()) {
                        return new RouteResult(REVISE, OP_REMOVE, null, extractName(name), null, null, false, null);
                    }
                }
                return new RouteResult(REVISE, OP_REMOVE, null, null, null, null, false, "未指明动作，由系统挑选");
            }
        }
        return RouteResult.of(CHAT);
    }

    /** 从"我的膝盖不舒服的深蹲"这类短语里提取末尾动作名。 */
    private static String extractName(String raw) {
        String s = clean(raw);
        if (s.isEmpty()) return null;
        // 去掉常见修饰前缀，取 2-6 字的末段
        String[] stop = {"我的", "那个", "这个", "今天的"};
        for (String p : stop) {
            if (s.startsWith(p)) s = s.substring(p.length());
        }
        // 去掉"把周三的深蹲"里残留的星期前缀（周X/星期X），避免定位串污染
        s = s.replaceAll("^(?:星期|周)[一二三四五六日天]", "");
        return s.isBlank() ? null : s;
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace("的", "").replace("了", "").replace("个", "")
                .replace("动作", "").replace("计划", "").trim();
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }
}
