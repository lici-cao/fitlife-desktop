package com.fitlife.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 模型配置（对应 application.yml 里的 ai.*）。
 *
 * <p>系统默认接入 DeepSeek 与豆包（火山方舟）的免费/低价模型，同时保留智谱 GLM 作为兜底。
 * 三家都提供 OpenAI 兼容的 /chat/completions 接口，所以只需配置 base url、key 与模型 id，
 * 新增一个厂商不用改任何 Java 代码。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiModelProperties {

    /** 默认模型 id，前端首次进入对话页时使用 */
    private String defaultModel = "deepseek-chat";

    /** 每分钟最多请求数（对话 / 计划生成共用） */
    private Integer rateLimit = 30;

    private List<Provider> providers = new ArrayList<>();

    @Data
    public static class Provider {
        /** 厂商标识：deepseek / doubao / glm */
        private String id;
        /** 展示名 */
        private String name;
        /** OpenAI 兼容的 chat/completions 完整地址 */
        private String apiUrl;
        /** API Key，留空表示未配置（前端会置灰该厂商的模型） */
        private String apiKey;
        /** 免费额度 / Key 申请页，前端用于提示 */
        private String docUrl;
        private Boolean enabled = true;
        private List<ModelSpec> models = new ArrayList<>();
    }

    @Data
    public static class ModelSpec {
        /** 传给接口的 model 参数 */
        private String id;
        /** 展示名 */
        private String name;
        /** 角标：免费 / 极速 / 推理 等 */
        private String tag;
        /** 是否属于免费额度模型 */
        private Boolean free = false;
        /** 该模型建议的最大输出 token */
        private Integer maxTokens = 1500;
        private Double temperature = 0.6;
        /** 一句话说明，前端模型选择器里展示 */
        private String note;
    }
}
