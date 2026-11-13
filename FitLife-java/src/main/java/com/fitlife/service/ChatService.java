package com.fitlife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.dto.*;
import com.fitlife.entity.ChatSession;
import com.fitlife.util.BodyMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话服务：把「能办事」和「能聊天」缝合成一条流。
 *
 * <pre>
 * 用户消息
 *   ├─ 命中意图（生成计划 / 运动搭配）→ 规则解析 + 动作库筛选 + 大模型编排 → 落库 → 推送计划卡片
 *   └─ 普通对话 → 动作库检索增强（RAG）→ 大模型流式输出 → 解析引用动作 → 推送动作卡片
 * </pre>
 *
 * 任何一环失败都会退回「规则兜底文案」，前端不会白屏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final LlmGateway llmGateway;
    private final AiService aiService;
    private final AiPlanService aiPlanService;
    private final ChatSessionService chatSessionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 流式任务线程池（守护线程，随应用退出） */
    private final ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "fitlife-chat");
        t.setDaemon(true);
        return t;
    });

    // ============================================================
    // 一、流式对话
    // ============================================================

    public SseEmitter stream(Long userId, ChatRequest req) {
        SseEmitter emitter = new SseEmitter(300_000L);
        pool.submit(() -> {
            StringBuilder full = new StringBuilder();
            // P3：服务端会话持久化（未登录不落库；失败仅告警，不阻断对话）
            ChatSession session = userId == null ? null
                    : chatSessionService.resolveSession(userId, req.getSessionId(), req.getMessage());
            try {
                String modelId = req.getModel();
                LlmGateway.Resolved target = null;
                try {
                    target = llmGateway.resolve(modelId);
                } catch (LlmGateway.LlmException e) {
                    // 没有可用 Key，先告知前端，再走兜底
                    send(emitter, event("error", "message", e.getMessage(), "fatal", false));
                    String fb = fallbackText(req);
                    send(emitter, event("fallback", "text", fb, "reason", e.getMessage()));
                    send(emitter, event("done", "action", "CHAT"));
                    persistTurn(session, req.getMessage(), fb, "CHAT_FALLBACK");
                    emitter.complete();
                    return;
                }

                send(emitter, event("meta",
                        "model", target.modelId(),
                        "modelName", target.model.getName(),
                        "provider", target.providerName()));

                // 1) 先看能不能直接办事（未登录时只做纯对话，避免计划落库失败）
                boolean allowAction = userId != null && (req.getAllowAction() == null || req.getAllowAction());
                Optional<PlanChatResponse> action = allowAction
                        ? aiPlanService.tryAction(userId, req.getMessage(), true)
                        : Optional.empty();

                if (action.isPresent()) {
                    PlanChatResponse r = action.get();
                    String reply = r.getReply() == null ? "" : r.getReply();
                    full.append(reply);
                    // 计划/搭配是「办事结果」，一次性推送，不做逐字模拟
                    if (!reply.isEmpty()) {
                        send(emitter, event("delta", "text", reply));
                    }
                    if (r.getPlan() != null) {
                        send(emitter, event("plan", "plan", r.getPlan()));
                    }
                    if (r.getMatch() != null) {
                        send(emitter, event("match", "match", r.getMatch()));
                    }
                    send(emitter, event("done", "action", r.getAction() == null ? "CHAT" : r.getAction()));
                    persistTurn(session, req.getMessage(), full.toString(),
                            r.getAction() == null ? "CHAT" : r.getAction());
                    emitter.complete();
                    return;
                }

                // 2) 普通对话：RAG + 流式输出
                List<ExerciseDTO> candidates = aiService.retrieveForChat(req.getMessage(), req.getContext());
                List<Map<String, String>> messages = buildMessages(req, candidates);

                int maxTokens = target.model.getMaxTokens() == null ? 1500 : target.model.getMaxTokens();
                double temperature = target.model.getTemperature() == null ? 0.6 : target.model.getTemperature();

                llmGateway.stream(target, messages, temperature, maxTokens, delta -> {
                    full.append(delta);
                    if (!send(emitter, event("delta", "text", delta))) {
                        throw new IllegalStateException("SSE_CLOSED");
                    }
                });

                List<ExerciseDTO> matched = aiService.extractReferenced(full.toString(), candidates);
                if (!matched.isEmpty()) {
                    send(emitter, event("exercises", "items", matched));
                }
                send(emitter, event("done", "action", "CHAT"));
                persistTurn(session, req.getMessage(), full.toString(), "CHAT");
                emitter.complete();
            } catch (Exception e) {
                if (!"SSE_CLOSED".equals(e.getMessage())) {
                    log.error("流式对话失败", e);
                    send(emitter, event("error", "message", friendly(e), "fatal", true));
                    send(emitter, event("fallback", "text", fallbackText(req), "reason", friendly(e)));
                }
                persistTurn(session, req.getMessage(), full.toString(), "CHAT_ERROR");
                try {
                    emitter.complete();
                } catch (Exception ignore) {
                    // 客户端已断开
                }
            }
        });
        return emitter;
    }

    // ============================================================
    // 二、非流式对话（不支持 SSE 的场合 / 接口调试）
    // ============================================================

    public Map<String, Object> chat(Long userId, ChatRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        ChatSession session = userId == null ? null
                : chatSessionService.resolveSession(userId, req.getSessionId(), req.getMessage());
        LlmGateway.Resolved target;
        try {
            target = llmGateway.resolve(req.getModel());
        } catch (LlmGateway.LlmException e) {
            out.put("reply", fallbackText(req));
            out.put("model", req.getModel());
            out.put("fallback", true);
            out.put("reason", e.getMessage());
            persistTurn(session, req.getMessage(), (String) out.get("reply"), "CHAT_FALLBACK");
            return out;
        }

        boolean allowAction = userId != null && (req.getAllowAction() == null || req.getAllowAction());
        if (allowAction) {
            Optional<PlanChatResponse> action = aiPlanService.tryAction(userId, req.getMessage(), true);
            if (action.isPresent()) {
                PlanChatResponse r = action.get();
                out.put("reply", r.getReply());
                out.put("action", r.getAction());
                if (r.getPlan() != null) out.put("plan", r.getPlan());
                if (r.getMatch() != null) out.put("match", r.getMatch());
                out.put("model", target.modelId());
                out.put("provider", target.providerName());
                persistTurn(session, req.getMessage(), r.getReply(),
                        r.getAction() == null ? "CHAT" : r.getAction());
                return out;
            }
        }

        try {
            List<ExerciseDTO> candidates = aiService.retrieveForChat(req.getMessage(), req.getContext());
            List<Map<String, String>> messages = buildMessages(req, candidates);
            int maxTokens = target.model.getMaxTokens() == null ? 1500 : target.model.getMaxTokens();
            double temperature = target.model.getTemperature() == null ? 0.6 : target.model.getTemperature();
            String reply = llmGateway.complete(target, messages, temperature, maxTokens);
            out.put("reply", reply);
            out.put("matchedExercises", aiService.extractReferenced(reply, candidates));
            out.put("model", target.modelId());
            out.put("provider", target.providerName());
            out.put("fallback", false);
            persistTurn(session, req.getMessage(), reply, "CHAT");
            return out;
        } catch (Exception e) {
            log.warn("非流式对话失败: {}", e.getMessage());
            out.put("reply", fallbackText(req));
            out.put("fallback", true);
            out.put("reason", friendly(e));
            out.put("model", target.modelId());
            out.put("provider", target.providerName());
            persistTurn(session, req.getMessage(), (String) out.get("reply"), "CHAT_ERROR");
            return out;
        }
    }

    // ============================================================
    // 三、内部工具
    // ============================================================

    /** P3：一轮对话留痕（user + assistant 两条；session 为 null 时直接跳过）。 */
    private void persistTurn(ChatSession session, String userMsg, String assistantMsg, String action) {
        if (session == null) {
            return;
        }
        try {
            chatSessionService.appendMessage(session, ChatSession.ROLE_USER, userMsg, action, null);
            chatSessionService.appendMessage(session, ChatSession.ROLE_ASSISTANT,
                    assistantMsg == null ? "" : assistantMsg, action, null);
            chatSessionService.touchSession(session, null);
        } catch (Exception e) {
            log.debug("对话留痕失败（忽略）：{}", e.getMessage());
        }
    }

    private List<Map<String, String>> buildMessages(ChatRequest req, List<ExerciseDTO> candidates) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(msg("system", aiService.buildSystemPrompt(req.getContext(), candidates)));

        List<ChatRequest.Message> history = req.getHistory();
        if (history != null && !history.isEmpty()) {
            // 只带最近 10 轮，控制 token
            int from = Math.max(0, history.size() - 10);
            for (int i = from; i < history.size(); i++) {
                ChatRequest.Message m = history.get(i);
                if (m == null || m.getContent() == null || m.getContent().isBlank()) {
                    continue;
                }
                String role = "assistant".equals(m.getRole()) ? "assistant" : "user";
                messages.add(msg(role, m.getContent()));
            }
        }
        messages.add(msg("user", req.getMessage()));
        return messages;
    }

    private Map<String, String> msg(String role, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** 组装一个 SSE 事件：{"type": "...", key: value, ...} */
    private String event(String type, Object... kv) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            payload.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"message\":\"事件序列化失败\"}";
        }
    }

    /** 推送一个 SSE 事件，返回 false 表示客户端已断开 */
    private boolean send(SseEmitter emitter, String json) {
        try {
            emitter.send(SseEmitter.event().data(json));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 推送终止: {}", e.getMessage());
            return false;
        }
    }

    private String friendly(Exception e) {
        if (e instanceof LlmGateway.LlmException) {
            return e.getMessage();
        }
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            return "AI 服务暂时不可用";
        }
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    /**
     * 大模型完全不可用时的规则兜底：至少让用户知道系统还能做什么。
     */
    private String fallbackText(ChatRequest req) {
        String message = req.getMessage() == null ? "" : req.getMessage();
        // 计算类问题：模型不在也能给确定数字，避免用「通用建议」糊过去
        if (BodyMetrics.CALC.matcher(message).find()) {
            BodyMetrics.Metrics m = BodyMetrics.from(req.getContext());
            if (m != null) {
                return "**AI 模型暂时不可用**，但下面这些指标是后端按你的档案直接算的，不是猜的：\n\n"
                        + BodyMetrics.toUserCard(m)
                        + "\n> 想要带公式推导的完整解释，填一个 API Key（DeepSeek / 豆包 / 智谱任选其一）后重新提问即可。";
            }
        }

        String tip = message != null && (message.contains("练") || message.contains("计划"))
                ? "你问的这类动作安排，我可以直接用动作库的规则引擎帮你排。"
                : "你可以先试试我内置的能力。";
        return "**AI 模型暂时不可用**，不过系统内置的规则引擎仍然在线：\n\n"
                + "· 说「帮我生成一周减脂计划」→ 直接按你的档案生成并保存训练计划\n"
                + "· 说「今天练胸怎么搭配」→ 从 100 个动作里挑一组带组次的训练\n"
                + "· 到「动作库」页可以按肌群 / 器械 / 难度筛选动作\n\n"
                + tip
                + "\n\n> 想恢复自由对话：在 `application.yml` 里填一个 API Key（DeepSeek / 豆包 / 智谱任选其一），或在环境变量里设置 `DEEPSEEK_API_KEY`。";
    }
}
