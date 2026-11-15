package com.fitlife.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.config.AiModelProperties;
import com.fitlife.dto.AiModelVO;
import com.fitlife.entity.AiCallLog;
import com.fitlife.repository.AiCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 统一的大模型调用网关。
 *
 * <p>DeepSeek、豆包（火山方舟）、智谱 GLM 都提供 OpenAI 兼容的
 * {@code POST {api-url}} 接口，请求体是 {@code {model, messages, temperature, max_tokens, stream}}，
 * 返回 {@code choices[0].message.content}；流式返回 {@code data: {...}} 分片。
 * 因此这里只做一件事：按模型 id 找到厂商配置，拼请求、读响应。
 *
 * <p>新增一家厂商只需要在 application.yml 的 {@code ai.providers} 下加一段配置，不用改代码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmGateway {

    private final AiModelProperties props;
    private final AiCallLogRepository callLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ===== P2 guarded 调用参数 =====
    /** 同厂商内重试次数（首次之外） */
    private static final int GUARDED_RETRIES = 2;
    /** 计划生成读超时：30s（旧路径保持 90s 不动） */
    private static final int GUARDED_READ_TIMEOUT_MS = 30_000;
    /** 连续失败 N 次后对该厂商熔断 30s */
    private static final int BREAKER_THRESHOLD = 3;
    private static final long BREAKER_OPEN_MS = 30_000;

    private final Map<String, AtomicInteger> providerFailStreak = new ConcurrentHashMap<>();
    private final Map<String, Long> providerOpenUntil = new ConcurrentHashMap<>();

    /** 大模型相关异常（未配置 Key、厂商返回错误、超时等） */
    public static class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 解析后的调用目标 */
    public static class Resolved {
        public final AiModelProperties.Provider provider;
        public final AiModelProperties.ModelSpec model;

        Resolved(AiModelProperties.Provider provider, AiModelProperties.ModelSpec model) {
            this.provider = provider;
            this.model = model;
        }

        public String modelId() {
            return model.getId();
        }

        public String providerName() {
            return provider.getName();
        }
    }

    // ============================================================
    // 一、模型清单
    // ============================================================

    public List<AiModelVO> listModels() {
        List<AiModelVO> list = new ArrayList<>();
        String defaultId = props.getDefaultModel();
        for (AiModelProperties.Provider p : enabledProviders()) {
            boolean hasKey = hasText(p.getApiKey());
            if (p.getModels() == null) {
                continue;
            }
            for (AiModelProperties.ModelSpec m : p.getModels()) {
                list.add(AiModelVO.builder()
                        .id(m.getId())
                        .name(m.getName() == null ? m.getId() : m.getName())
                        .providerId(p.getId())
                        .providerName(p.getName())
                        .tag(m.getTag())
                        .free(Boolean.TRUE.equals(m.getFree()))
                        .available(hasKey)
                        .isDefault(m.getId().equals(defaultId))
                        .note(hasKey ? m.getNote() : "未配置 " + envHint(p) + "，暂时不可用")
                        .build());
            }
        }
        return list;
    }

    /** 默认模型 id：配置的默认模型没有 Key 时，自动落到第一个可用模型 */
    public String defaultModelId() {
        List<AiModelVO> models = listModels();
        for (AiModelVO m : models) {
            if (m.getId().equals(props.getDefaultModel()) && Boolean.TRUE.equals(m.getAvailable())) {
                return m.getId();
            }
        }
        for (AiModelVO m : models) {
            if (Boolean.TRUE.equals(m.getAvailable())) {
                return m.getId();
            }
        }
        return props.getDefaultModel();
    }

    public boolean hasAvailableModel() {
        return listModels().stream().anyMatch(m -> Boolean.TRUE.equals(m.getAvailable()));
    }

    // ============================================================
    // 二、解析模型
    // ============================================================

    public Resolved resolve(String modelId) {
        String wanted = hasText(modelId) ? modelId.trim() : props.getDefaultModel();

        // 1) 精确命中
        for (AiModelProperties.Provider p : enabledProviders()) {
            if (p.getModels() == null) {
                continue;
            }
            for (AiModelProperties.ModelSpec m : p.getModels()) {
                if (m.getId().equals(wanted)) {
                    if (!hasText(p.getApiKey())) {
                        throw new LlmException(p.getName() + " 尚未配置 API Key，请在 application.yml 里填写 "
                                + envHint(p) + "，或直接切换到其他模型");
                    }
                    return new Resolved(p, m);
                }
            }
        }

        // 2) 传了不认识的 id：回落到默认模型 / 第一个可用模型
        for (AiModelProperties.Provider p : enabledProviders()) {
            if (!hasText(p.getApiKey()) || p.getModels() == null) {
                continue;
            }
            for (AiModelProperties.ModelSpec m : p.getModels()) {
                if (m.getId().equals(props.getDefaultModel())) {
                    log.warn("未知模型 {}，回落到默认模型 {}", wanted, m.getId());
                    return new Resolved(p, m);
                }
            }
        }
        for (AiModelProperties.Provider p : enabledProviders()) {
            if (!hasText(p.getApiKey()) || p.getModels() == null) {
                continue;
            }
            for (AiModelProperties.ModelSpec m : p.getModels()) {
                log.warn("未知模型 {}，回落到 {}", wanted, m.getId());
                return new Resolved(p, m);
            }
        }
        throw new LlmException("当前没有可用的大模型：请在 application.yml 里为 DeepSeek、豆包或智谱至少配置一个 API Key");
    }

    // ============================================================
    // 三、一次性调用
    // ============================================================

    public String complete(Resolved target, List<Map<String, String>> messages,
                           double temperature, int maxTokens) {
        HttpURLConnection conn = null;
        try {
            conn = open(target, messages, temperature, maxTokens, false);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new LlmException(target.providerName() + " 返回 " + code + "：" + readError(conn));
            }
            String body = readAll(conn.getInputStream());
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new LlmException(target.providerName() + " 返回格式异常："
                        + root.path("error").path("message").asText("没有 choices 字段"));
            }
            JsonNode message = choices.get(0).path("message");
            String content = message.path("content").asText("");
            if (content.isBlank()) {
                content = message.path("reasoning_content").asText("");
            }
            if (content.isBlank()) {
                throw new LlmException(target.providerName() + " 没有返回有效文本");
            }
            return content.trim();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException(target.providerName() + " 调用失败：" + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ============================================================
    // 三B、可靠性调用（P2：重试 + 跨厂商转移 + 熔断 + 计量）
    // ============================================================

    /** guarded 调用结果：正文 + 实际使用的模型 + 用量 */
    public record GuardedResult(String content, String modelId, String providerName,
                                int attempts, String fallbackFrom,
                                Integer promptTokens, Integer completionTokens, Integer totalTokens,
                                int latencyMs) {
    }

    /** 带 HTTP 状态码的内部异常，用于区分「可重试」与「直接转移」 */
    private static class LlmHttpStatusException extends RuntimeException {
        final int status;

        LlmHttpStatusException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /**
     * 计划生成主链路专用（图 2 第 ⑧ 步）。
     *
     * <p>候选链 = 首选模型 → 其他可用模型（跨厂商）；同厂商内最多重试
     * {@value #GUARDED_RETRIES} 次（指数退避 1s/2s），仅对 IO/超时/5xx/429 重试，
     * 401/403/404 直接转移；厂商连续 3 败熔断 30s；每次落选与最终结果都写
     * ai_call_log。全部失败抛 LlmException，由编排器降级模板。
     */
    public GuardedResult completeGuarded(List<Map<String, String>> messages, double temperature,
                                         int maxTokens, String preferredModelId,
                                         String endpoint, Long userId) {
        List<Resolved> chain = new ArrayList<>();
        String preferred = hasText(preferredModelId) ? preferredModelId.trim() : defaultModelId();
        try {
            chain.add(resolve(preferred));
        } catch (LlmException e) {
            log.warn("首选模型 {} 不可用: {}", preferred, e.getMessage());
        }
        for (AiModelVO m : listModels()) {
            if (!Boolean.TRUE.equals(m.getAvailable()) || m.getId().equals(preferred)) {
                continue;
            }
            try {
                Resolved r = resolve(m.getId());
                if (chain.stream().noneMatch(c -> c.modelId().equals(r.modelId()))) {
                    chain.add(r);
                }
            } catch (LlmException ignore) {
                // 理论可用但解析失败，跳过
            }
        }
        if (chain.isEmpty()) {
            logCallRaw(endpoint, userId, preferred, 0, null, 0, false, "NO_PROVIDER");
            throw new LlmException("当前没有可用的大模型：请为任一厂商配置 API Key");
        }

        long started = System.currentTimeMillis();
        String lastError = "未知错误";
        String lastErrorClass = "PROVIDER_ERROR";
        int maxAttempts = 1 + GUARDED_RETRIES;
        for (int i = 0; i < chain.size(); i++) {
            Resolved target = chain.get(i);
            if (isBreakerOpen(target.provider.getId())) {
                log.warn("厂商 {} 熔断中，跳过", target.providerName());
                continue;
            }
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    Once once = tryOnce(target, messages, temperature, maxTokens, GUARDED_READ_TIMEOUT_MS);
                    int latency = (int) (System.currentTimeMillis() - started);
                    String fallbackFrom = (i > 0 || !target.modelId().equals(preferred)) ? preferred : null;
                    recordSuccess(target.provider.getId());
                    logCall(endpoint, userId, target, attempt, fallbackFrom, once, latency, true, null);
                    return new GuardedResult(once.content, target.modelId(), target.providerName(),
                            attempt, fallbackFrom, once.promptTokens, once.completionTokens,
                            once.totalTokens, latency);
                } catch (LlmHttpStatusException e) {
                    lastError = e.getMessage();
                    lastErrorClass = "HTTP_" + e.status;
                    recordFailure(target.provider.getId());
                    // 限流/服务端错误：同厂商退避重试；401/403/404 或重试耗尽：转移下一候选
                    boolean retryable = e.status == 429 || e.status >= 500;
                    if (retryable && attempt < maxAttempts) {
                        sleepBackoff(attempt);
                        continue;
                    }
                    break;
                } catch (SocketTimeoutException e) {
                    lastError = "读超时";
                    lastErrorClass = "TIMEOUT";
                    recordFailure(target.provider.getId());
                    if (attempt < maxAttempts) {
                        sleepBackoff(attempt);
                        continue;
                    }
                    break;
                } catch (IOException e) {
                    lastError = e.getMessage();
                    lastErrorClass = "IO";
                    recordFailure(target.provider.getId());
                    if (attempt < maxAttempts) {
                        sleepBackoff(attempt);
                        continue;
                    }
                    break;
                }
            }
            // 本候选穷尽：记失败行，再试下一厂商
            logCall(endpoint, userId, target, maxAttempts, preferred,
                    null, (int) (System.currentTimeMillis() - started), false, lastErrorClass);
        }
        throw new LlmException("AI 服务全部不可用（最后错误：" + lastError + "）");
    }

    private record Once(String content, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    }

    /** 单次 HTTP 调用：解析正文与 usage；带状态码异常供上层分类 */
    private Once tryOnce(Resolved target, List<Map<String, String>> messages,
                         double temperature, int maxTokens, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = open(target, messages, temperature, maxTokens, false);
            conn.setReadTimeout(readTimeoutMs);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new LlmHttpStatusException(code,
                        target.providerName() + " 返回 " + code + "：" + readError(conn));
            }
            String body = readAll(conn.getInputStream());
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new LlmHttpStatusException(502, target.providerName() + " 返回格式异常：没有 choices 字段");
            }
            JsonNode message = choices.get(0).path("message");
            String content = message.path("content").asText("");
            if (content.isBlank()) {
                content = message.path("reasoning_content").asText("");
            }
            if (content.isBlank()) {
                throw new LlmHttpStatusException(502, target.providerName() + " 没有返回有效文本");
            }
            JsonNode usage = root.path("usage");
            Integer pt = usage.path("prompt_tokens").isNumber() ? usage.path("prompt_tokens").asInt() : null;
            Integer ct = usage.path("completion_tokens").isNumber() ? usage.path("completion_tokens").asInt() : null;
            Integer tt = usage.path("total_tokens").isNumber() ? usage.path("total_tokens").asInt()
                    : (pt != null || ct != null ? (pt == null ? 0 : pt) + (ct == null ? 0 : ct) : null);
            return new Once(content.trim(), pt, ct, tt);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(1000L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isBreakerOpen(String providerId) {
        Long until = providerOpenUntil.get(providerId);
        return until != null && until > System.currentTimeMillis();
    }

    private void recordFailure(String providerId) {
        int streak = providerFailStreak.computeIfAbsent(providerId, k -> new AtomicInteger())
                .incrementAndGet();
        if (streak >= BREAKER_THRESHOLD) {
            providerOpenUntil.put(providerId, System.currentTimeMillis() + BREAKER_OPEN_MS);
            log.warn("厂商 {} 连续失败 {} 次，熔断 {} 秒", providerId, streak, BREAKER_OPEN_MS / 1000);
        }
    }

    private void recordSuccess(String providerId) {
        AtomicInteger c = providerFailStreak.get(providerId);
        if (c != null) {
            c.set(0);
        }
        providerOpenUntil.remove(providerId);
    }

    /** 计量落库：失败不影响主流程 */
    private void logCall(String endpoint, Long userId, Resolved target, int attempts, String fallbackFrom,
                         Once once, int latencyMs, boolean success, String errorClass) {
        try {
            callLogRepository.save(AiCallLog.builder()
                    .endpoint(endpoint == null ? "UNKNOWN" : endpoint)
                    .userId(userId)
                    .modelId(target.modelId())
                    .provider(target.provider.getId())
                    .attempts(attempts)
                    .fallbackFrom(fallbackFrom)
                    .latencyMs(latencyMs)
                    .promptTokens(once == null ? null : once.promptTokens)
                    .completionTokens(once == null ? null : once.completionTokens)
                    .totalTokens(once == null ? null : once.totalTokens)
                    .success(success ? 1 : 0)
                    .errorClass(errorClass)
                    .build());
        } catch (Exception e) {
            log.warn("ai_call_log 写入失败: {}", e.getMessage());
        }
    }

    /** 没有任何可用厂商时的观测行（无 provider/target 可记） */
    private void logCallRaw(String endpoint, Long userId, String modelId, int attempts, String fallbackFrom,
                             int latencyMs, boolean success, String errorClass) {
        try {
            callLogRepository.save(AiCallLog.builder()
                    .endpoint(endpoint == null ? "UNKNOWN" : endpoint)
                    .userId(userId)
                    .modelId(modelId)
                    .provider("none")
                    .attempts(attempts)
                    .fallbackFrom(fallbackFrom)
                    .latencyMs(latencyMs)
                    .success(success ? 1 : 0)
                    .errorClass(errorClass)
                    .build());
        } catch (Exception e) {
            log.warn("ai_call_log 写入失败: {}", e.getMessage());
        }
    }

    // ============================================================
    // 四、流式调用（SSE）
    // ============================================================

    /**
     * 流式输出：每收到一个分片就回调一次 onDelta。
     * 推理型模型（deepseek-reasoner 等）只把正文回流；若全程没有正文，则回落输出思考内容。
     */
    public void stream(Resolved target, List<Map<String, String>> messages,
                       double temperature, int maxTokens, Consumer<String> onDelta) {
        HttpURLConnection conn = null;
        StringBuilder reasoning = new StringBuilder();
        boolean hasContent = false;
        try {
            conn = open(target, messages, temperature, maxTokens, true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new LlmException(target.providerName() + " 返回 " + code + "：" + readError(conn));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith(":")) {
                        continue;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode node;
                    try {
                        node = objectMapper.readTree(data);
                    } catch (Exception ignore) {
                        continue;
                    }
                    JsonNode choices = node.path("choices");
                    if (!choices.isArray() || choices.isEmpty()) {
                        continue;
                    }
                    JsonNode delta = choices.get(0).path("delta");
                    String content = delta.path("content").asText("");
                    if (!content.isEmpty()) {
                        hasContent = true;
                        onDelta.accept(content);
                    } else {
                        String think = delta.path("reasoning_content").asText("");
                        if (!think.isEmpty()) {
                            reasoning.append(think);
                        }
                    }
                }
            }
            if (!hasContent && reasoning.length() > 0) {
                onDelta.accept(reasoning.toString());
            }
        } catch (LlmException e) {
            throw e;
        } catch (IOException e) {
            throw new LlmException(target.providerName() + " 流式调用中断：" + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ============================================================
    // 五、内部工具
    // ============================================================

    private HttpURLConnection open(Resolved target, List<Map<String, String>> messages,
                                  double temperature, int maxTokens, boolean streamFlag) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", target.modelId());
        body.put("messages", messages);
        body.put("stream", streamFlag);
        body.put("max_tokens", maxTokens > 0 ? maxTokens : 1500);
        // 推理型模型不接受 temperature 参数，传了会被厂商拒绝
        if (!isReasoningModel(target.modelId())) {
            body.put("temperature", temperature);
        }

        String payload = objectMapper.writeValueAsString(body);
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(target.provider.getApiUrl()).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(streamFlag ? 180000 : 90000);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept", streamFlag ? "text/event-stream" : "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + target.provider.getApiKey());

        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
            os.flush();
        }
        return conn;
    }

    private boolean isReasoningModel(String modelId) {
        if (modelId == null) {
            return false;
        }
        String id = modelId.toLowerCase();
        return id.contains("reasoner") || id.contains("thinking") || id.contains("reasoning");
    }

    private String readError(HttpURLConnection conn) {
        try (InputStream es = conn.getErrorStream()) {
            if (es == null) {
                return "无错误详情";
            }
            String raw = readAll(es);
            try {
                JsonNode node = objectMapper.readTree(raw);
                String msg = node.path("error").path("message").asText("");
                if (!msg.isBlank()) {
                    return msg;
                }
            } catch (Exception ignore) {
                // 不是 JSON，直接返回原文
            }
            return raw.length() > 300 ? raw.substring(0, 300) : raw;
        } catch (Exception e) {
            return "读取错误信息失败：" + e.getMessage();
        }
    }

    private String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private List<AiModelProperties.Provider> enabledProviders() {
        List<AiModelProperties.Provider> list = new ArrayList<>();
        if (props.getProviders() == null) {
            return list;
        }
        for (AiModelProperties.Provider p : props.getProviders()) {
            if (Boolean.FALSE.equals(p.getEnabled())) {
                continue;
            }
            if (!hasText(p.getApiUrl())) {
                continue;
            }
            list.add(p);
        }
        return list;
    }

    private String envHint(AiModelProperties.Provider p) {
        if ("deepseek".equals(p.getId())) {
            return "DEEPSEEK_API_KEY";
        }
        if ("doubao".equals(p.getId())) {
            return "ARK_API_KEY";
        }
        if ("glm".equals(p.getId())) {
            return "ZHIPU_API_KEY";
        }
        return p.getId() + " 的 API Key";
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
