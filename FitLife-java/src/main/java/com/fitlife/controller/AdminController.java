package com.fitlife.controller;

import com.fitlife.common.BusinessException;
import com.fitlife.dto.ApiResponse;
import com.fitlife.entity.AiCallLog;
import com.fitlife.entity.AuditLog;
import com.fitlife.entity.KnowledgeItem;
import com.fitlife.entity.KnowledgeRule;
import com.fitlife.entity.PromptTemplate;
import com.fitlife.entity.User;
import com.fitlife.repository.AiCallLogRepository;
import com.fitlife.repository.AuditLogRepository;
import com.fitlife.repository.KnowledgeItemRepository;
import com.fitlife.repository.KnowledgeRuleRepository;
import com.fitlife.repository.PromptTemplateRepository;
import com.fitlife.repository.UserRepository;
import com.fitlife.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台管理接口。
 *
 * <p>原系统没有任何管理员能力：{@code users} 表连 role 字段都没有，动作库、用户、
 * 审计都只能靠 SQL 手改。本控制器只做 P1 范围内的最小集合——用户列表/启停/角色、
 * 审计日志查询；每一个写操作都落审计，且管理员不能改自己的角色或停用自己
 * （避免把唯一管理员锁在门外）。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditLogService auditLogService;
    private final KnowledgeItemRepository knowledgeItemRepository;
    private final KnowledgeRuleRepository knowledgeRuleRepository;
    private final PromptTemplateRepository promptTemplateRepository;
    private final AiCallLogRepository aiCallLogRepository;

    @GetMapping("/users")
    public ResponseEntity<ApiResponse> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pr = pageRequest(page, size, Sort.by(Sort.Direction.DESC, "id"));
        Page<User> result = (keyword == null || keyword.isBlank())
                ? userRepository.findAll(pr)
                : userRepository.findByUsernameContainingIgnoreCase(keyword.trim(), pr);

        List<Map<String, Object>> rows = result.getContent().stream().map(u -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", u.getId());
            row.put("username", u.getUsername());
            row.put("role", u.getRole());
            row.put("status", u.getStatus());
            row.put("createdAt", u.getCreatedAt());
            row.put("lastLoginAt", u.getLastLoginAt());
            return row;
        }).toList();

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "rows", rows,
                "total", result.getTotalElements(),
                "page", result.getNumber(),
                "size", result.getSize())));
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<ApiResponse> auditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<AuditLog> result = auditLogRepository.findAllByOrderByIdDesc(pageRequest(page, size, null));
        List<Map<String, Object>> rows = result.getContent().stream().map(a -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("userId", a.getUserId());
            row.put("username", a.getUsername());
            row.put("action", a.getAction());
            row.put("target", a.getTarget());
            row.put("targetId", a.getTargetId());
            row.put("detail", a.getDetail());
            row.put("ip", a.getIp());
            row.put("createdAt", a.getCreatedAt());
            return row;
        }).toList();

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "rows", rows,
                "total", result.getTotalElements(),
                "page", result.getNumber(),
                "size", result.getSize())));
    }

    @PatchMapping("/users/{id}/status")
    @Transactional
    public ResponseEntity<ApiResponse> updateStatus(Authentication auth, @PathVariable Long id,
                                                    @RequestParam String status) {
        User me = currentUser(auth);
        if (me.getId().equals(id)) {
            throw new BusinessException(400, "不能停用/启用当前登录的管理员账号");
        }
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!User.STATUS_ACTIVE.equals(normalized) && !User.STATUS_DISABLED.equals(normalized)) {
            throw new BusinessException(400, "status 只能是 ACTIVE 或 DISABLED");
        }
        User target = userRepository.findById(id).orElseThrow(() -> new BusinessException(404, "用户不存在"));
        target.setStatus(normalized);
        userRepository.save(target);

        auditLogService.log(me.getId(), me.getUsername(), "USER_STATUS_CHANGE", "USER", id,
                target.getUsername() + " -> " + normalized, null);
        return ResponseEntity.ok(ApiResponse.success("已更新账号状态"));
    }

    @PatchMapping("/users/{id}/role")
    @Transactional
    public ResponseEntity<ApiResponse> updateRole(Authentication auth, @PathVariable Long id,
                                                  @RequestParam String role) {
        User me = currentUser(auth);
        if (me.getId().equals(id)) {
            throw new BusinessException(400, "不能修改当前登录管理员的角色");
        }
        String normalized = role == null ? "" : role.trim().toUpperCase();
        if (!User.ROLE_USER.equals(normalized) && !User.ROLE_ADMIN.equals(normalized)) {
            throw new BusinessException(400, "role 只能是 USER 或 ADMIN");
        }
        User target = userRepository.findById(id).orElseThrow(() -> new BusinessException(404, "用户不存在"));
        target.setRole(normalized);
        userRepository.save(target);

        auditLogService.log(me.getId(), me.getUsername(), "USER_ROLE_CHANGE", "USER", id,
                target.getUsername() + " -> " + normalized, null);
        return ResponseEntity.ok(ApiResponse.success("已更新角色"));
    }

    // ============================================================
    // P2：AI 知识资产只读端点（知识条目 / 规则阈值 / Prompt 模板 / 调用计量）
    // 本阶段只读——写入口留给 P5 后台管理界面，避免边跑边改规则。
    // ============================================================

    /** 健身知识库条目（含停用项，供核对检索命中） */
    @GetMapping("/knowledge-items")
    public ResponseEntity<ApiResponse> knowledgeItems(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<KnowledgeItem> all = knowledgeItemRepository.findAll().stream()
                .filter(k -> type == null || type.isBlank() || type.equalsIgnoreCase(k.getType()))
                .toList();
        PageRequest pr = pageRequest(page, size, null);
        int from = (int) Math.min(all.size(), pr.getOffset());
        int to = Math.min(all.size(), from + pr.getPageSize());
        List<Map<String, Object>> rows = all.subList(from, to).stream().map(k -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", k.getId());
            row.put("code", k.getCode());
            row.put("type", k.getType());
            row.put("title", k.getTitle());
            row.put("content", k.getContent());
            row.put("goalScope", k.getGoalScope());
            row.put("keywords", k.getKeywords());
            row.put("severity", k.getSeverity());
            row.put("enabled", k.getEnabled());
            row.put("version", k.getVersion());
            return row;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "rows", rows, "total", all.size(), "page", pr.getPageNumber(), "size", pr.getPageSize())));
    }

    /** 规则校验器阈值（knowledge_rule.expression 为 JSON） */
    @GetMapping("/knowledge-rules")
    public ResponseEntity<ApiResponse> knowledgeRules() {
        List<Map<String, Object>> rows = knowledgeRuleRepository.findAll().stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("code", r.getCode());
            row.put("name", r.getName());
            row.put("category", r.getCategory());
            row.put("expression", r.getExpression());
            row.put("severity", r.getSeverity());
            row.put("enabled", r.getEnabled());
            row.put("version", r.getVersion());
            row.put("note", r.getNote());
            return row;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    /** 指定 code 的 Prompt 模板全部版本（active=1 为当前生效版） */
    @GetMapping("/prompt-templates/{code}")
    public ResponseEntity<ApiResponse> promptTemplates(@PathVariable String code) {
        List<PromptTemplate> list = promptTemplateRepository.findByCodeOrderByVersionDesc(code);
        if (list.isEmpty()) {
            throw new BusinessException(404, "模板不存在: " + code);
        }
        List<Map<String, Object>> rows = list.stream().map(t -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.getId());
            row.put("code", t.getCode());
            row.put("version", t.getVersion());
            row.put("name", t.getName());
            row.put("body", t.getBody());
            row.put("active", t.getActive());
            row.put("note", t.getNote());
            row.put("createdAt", t.getCreatedAt());
            return row;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    /** 大模型调用计量（按端点过滤，倒序分页） */
    @GetMapping("/ai-calls")
    public ResponseEntity<ApiResponse> aiCalls(
            @RequestParam(defaultValue = "PLAN_GENERATE") String endpoint,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AiCallLog> result = aiCallLogRepository.findByEndpointOrderByIdDesc(
                endpoint.trim().toUpperCase(), pageRequest(page, size, null));
        List<Map<String, Object>> rows = result.getContent().stream().map(c -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("userId", c.getUserId());
            row.put("modelId", c.getModelId());
            row.put("provider", c.getProvider());
            row.put("attempts", c.getAttempts());
            row.put("fallbackFrom", c.getFallbackFrom());
            row.put("latencyMs", c.getLatencyMs());
            row.put("promptTokens", c.getPromptTokens());
            row.put("completionTokens", c.getCompletionTokens());
            row.put("totalTokens", c.getTotalTokens());
            row.put("success", c.getSuccess());
            row.put("errorClass", c.getErrorClass());
            row.put("createdAt", c.getCreatedAt());
            return row;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "rows", rows, "total", result.getTotalElements(),
                "page", result.getNumber(), "size", result.getSize())));
    }

    private User currentUser(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            throw new BusinessException(401, "未登录或登录已过期");
        }
        return user;
    }

    private PageRequest pageRequest(int page, int size, Sort sort) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return sort == null ? PageRequest.of(p, s) : PageRequest.of(p, s, sort);
    }
}