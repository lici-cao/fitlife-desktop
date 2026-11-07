package com.fitlife.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 前端模型选择器里的一个选项。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiModelVO {
    /** 模型 id（请求时回传） */
    private String id;
    /** 展示名 */
    private String name;
    private String providerId;
    private String providerName;
    /** 角标：免费 / 极速 / 推理 */
    private String tag;
    private Boolean free;
    /** 该厂商是否已配置 API Key */
    private Boolean available;
    /** 默认模型 */
    private Boolean isDefault;
    /** 提示文案，例如「未配置 DEEPSEEK_API_KEY」 */
    private String note;
}
