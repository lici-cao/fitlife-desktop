package com.fitlife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.entity.PromptTemplate;
import com.fitlife.repository.PromptTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Prompt 模板版本化渲染（P2）。
 *
 * <p>从 prompt_template 表取 code 的 active 版本，替换 {{key}} 占位符；
 * 表内无记录或渲染异常时退回调用方提供的内置模板，保证「模板即数据」
 * 但不引入新的单点故障。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateService {

    private final PromptTemplateRepository promptTemplateRepository;
    private final ObjectMapper objectMapper;

    /** @return 渲染结果；usedFallback=true 表示 DB 模板不可用、用了内置正文 */
    public record Rendered(String prompt, String templateCode, Integer templateVersion, boolean usedFallback) {
    }

    public Rendered render(String code, Map<String, String> vars, String builtinBody) {
        try {
            PromptTemplate tpl = promptTemplateRepository.findByCodeAndActive(code, 1).orElse(null);
            if (tpl != null && tpl.getBody() != null && !tpl.getBody().isBlank()) {
                return new Rendered(apply(tpl.getBody(), vars), code, tpl.getVersion(), false);
            }
        } catch (Exception e) {
            log.warn("Prompt 模板读取失败 code={}: {}，使用内置模板", code, e.getMessage());
        }
        return new Rendered(apply(builtinBody, vars), code, null, true);
    }

    /** 简单 {{key}} 替换；未提供的 key 保留原样，便于排查模板与变量不匹配 */
    private String apply(String body, Map<String, String> vars) {
        if (vars == null || vars.isEmpty()) {
            return body;
        }
        String out = body;
        for (Map.Entry<String, String> en : vars.entrySet()) {
            out = out.replace("{{" + en.getKey() + "}}", en.getValue() == null ? "" : en.getValue());
        }
        return out;
    }

    public String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
