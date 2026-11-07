package com.fitlife.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.config.AiModelProperties;
import com.fitlife.dto.AiModelVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

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
