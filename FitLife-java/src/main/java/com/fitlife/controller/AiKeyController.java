package com.fitlife.controller;

import com.fitlife.dto.AiModelVO;
import com.fitlife.dto.ApiResponse;
import com.fitlife.service.AiKeyStore;
import com.fitlife.service.LlmGateway;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型 API Key 页面化管理接口。
 *
 * <ul>
 *   <li>GET    /api/ai/keys              各厂商 Key 状态（脱敏） + 最新模型可用性</li>
 *   <li>POST   /api/ai/keys              保存某个厂商的 Key（立即生效，无需重启）</li>
 *   <li>DELETE /api/ai/keys/{providerId} 清除某个厂商的 Key</li>
 *   <li>POST   /api/ai/keys/verify       用已保存的 Key 试发一条请求，确认是否可用</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai/keys")
@RequiredArgsConstructor
public class AiKeyController {

    private final AiKeyStore keyStore;
    private final LlmGateway llmGateway;

    @Data
    public static class SaveRequest {
        private String providerId;
        private String apiKey;
    }

    @Data
    public static class VerifyRequest {
        private String providerId;
    }

    /** Key 状态 + 模型清单（保存后前端可直接用这份数据刷新模型选择器） */
    @GetMapping
    public ResponseEntity<ApiResponse> list() {
        return ResponseEntity.ok(ApiResponse.success(payload()));
    }

    /** 保存 Key */
    @PostMapping
    public ResponseEntity<ApiResponse> save(@RequestBody SaveRequest req) {
        if (req == null || req.getProviderId() == null || req.getProviderId().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("缺少 providerId"));
        }
        try {
            keyStore.save(req.getProviderId(), req.getApiKey());
            Map<String, Object> data = payload();
            data.put("message", "已保存，立即生效");
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("保存失败：" + e.getMessage()));
        }
    }

    /** 清除 Key */
    @DeleteMapping("/{providerId}")
    public ResponseEntity<ApiResponse> clear(@PathVariable String providerId) {
        try {
            keyStore.clear(providerId);
            Map<String, Object> data = payload();
            data.put("message", "已清除该厂商的 API Key");
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("清除失败：" + e.getMessage()));
        }
    }

    /** 试调一次，验证 Key 是否真的能用 */
    @PostMapping("/verify")
    public ResponseEntity<ApiResponse> verify(@RequestBody VerifyRequest req) {
        String providerId = req == null ? null : req.getProviderId();
        if (providerId == null || providerId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("缺少 providerId"));
        }
        try {
            String modelId = null;
            for (AiModelVO m : llmGateway.listModels()) {
                if (providerId.equals(m.getProviderId())) {
                    modelId = m.getId();
                    break;
                }
            }
            if (modelId == null) {
                return ResponseEntity.badRequest().body(ApiResponse.error("该厂商没有可用模型"));
            }
            LlmGateway.Resolved target = llmGateway.resolve(modelId);
            String reply = llmGateway.complete(target,
                    List.of(Map.of("role", "user", "content", "ping")), 0.0, 16);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ok", true);
            data.put("model", target.modelId());
            data.put("providerName", target.providerName());
            data.put("reply", reply.length() > 60 ? reply.substring(0, 60) : reply);
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (Exception e) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ok", false);
            data.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    private Map<String, Object> payload() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("providers", keyStore.status());
        data.put("models", llmGateway.listModels());
        data.put("defaultModel", llmGateway.defaultModelId());
        data.put("configFile", keyStore.configFile().toString());
        return data;
    }
}
