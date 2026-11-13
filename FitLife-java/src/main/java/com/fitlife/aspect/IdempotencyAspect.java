package com.fitlife.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.common.BusinessException;
import com.fitlife.common.annotation.Idempotent;
import com.fitlife.dto.ApiResponse;
import com.fitlife.entity.IdempotencyRecord;
import com.fitlife.entity.User;
import com.fitlife.service.IdempotencyService;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 幂等切面。
 *
 * <p>时序：认领 key（独立事务）→ 执行业务 → 成功则落盘响应体；异常则释放 key。
 * 幂等的最终防线是数据库唯一键 {@code (user_id, idem_key)}，切面只负责把
 * 「撞键」翻译成回放或 409。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class IdempotencyAspect {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "Idempotent-Replay";
    private static final int MAX_KEY_LENGTH = 128;

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @Around("@annotation(com.fitlife.common.annotation.Idempotent)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Idempotent idempotent = signature.getMethod().getAnnotation(Idempotent.class);

        HttpServletRequest request = currentRequest();
        if (request == null) {
            return pjp.proceed();
        }

        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            if (idempotent.required()) {
                throw new BusinessException(400, "缺少 " + HEADER + " 请求头");
            }
            return pjp.proceed();
        }
        key = key.trim();
        if (key.length() > MAX_KEY_LENGTH) {
            throw new BusinessException(400, HEADER + " 长度不能超过 " + MAX_KEY_LENGTH);
        }

        User user = currentUser();
        if (user == null || user.getId() == null) {
            // 未登录时交给鉴权链（会返回 401），这里不消费 key
            return pjp.proceed();
        }

        Long userId = user.getId();
        String endpoint = request.getMethod() + " " + request.getRequestURI();
        String requestHash = hash(endpoint, pjp.getArgs());

        boolean claimed;
        try {
            claimed = idempotencyService.claim(userId, key, endpoint, requestHash);
        } catch (Exception e) {
            // 幂等表不可用不应该阻断业务：记日志后放行（最终一致性让位于可用性）
            log.warn("幂等键认领异常，本次请求按非幂等处理: user={}, key={}, err={}", userId, key, e.getMessage());
            claimed = true;
        }

        if (!claimed) {
            IdempotencyRecord existing = idempotencyService.find(userId, key).orElse(null);
            if (existing == null) {
                // 极端并发：记录刚被清理掉，重新认领一次；仍失败就按非幂等放行
                if (!idempotencyService.claim(userId, key, endpoint, requestHash)) {
                    log.warn("幂等键二次认领失败，按非幂等处理: user={}, key={}", userId, key);
                    return pjp.proceed();
                }
            } else {
                if (!requestHash.equals(existing.getRequestHash())) {
                    throw new BusinessException(409, "同一个 " + HEADER + " 不能用于不同的请求内容");
                }
                if (existing.getStatus() != null && existing.getStatus() == 1 && existing.getResponseBody() != null) {
                    return replay(existing);
                }
                if (isStale(existing, idempotent.staleSeconds())) {
                    log.warn("幂等记录僵死，重新认领: user={}, key={}", userId, key);
                    idempotencyService.release(userId, key);
                    idempotencyService.claim(userId, key, endpoint, requestHash);
                } else {
                    throw new BusinessException(409, "相同请求正在处理中，请勿重复提交");
                }
            }
        }

        try {
            Object result = pjp.proceed();
            cacheIfSuccessful(userId, key, result);
            return result;
        } catch (Throwable t) {
            try {
                idempotencyService.release(userId, key);
            } catch (Exception e) {
                log.warn("释放幂等键失败: user={}, key={}, err={}", userId, key, e.getMessage());
            }
            throw t;
        }
    }

    private void cacheIfSuccessful(Long userId, String key, Object result) {
        if (!(result instanceof ResponseEntity<?> entity) || entity.getBody() == null) {
            return;
        }
        Object body = entity.getBody();
        if (body instanceof ApiResponse response && response.getCode() >= 400) {
            // 业务失败不缓存：释放 key，允许用户修正参数后用同一个 key 重试
            idempotencyService.release(userId, key);
            return;
        }
        try {
            idempotencyService.markDone(userId, key, objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            log.warn("缓存幂等响应失败: user={}, key={}, err={}", userId, key, e.getMessage());
        }
    }

    private Object replay(IdempotencyRecord record) throws Exception {
        ApiResponse body = objectMapper.readValue(record.getResponseBody(), ApiResponse.class);
        log.info("幂等命中，回放响应: user={}, key={}, endpoint={}",
                record.getUserId(), record.getIdemKey(), record.getEndpoint());
        return ResponseEntity.ok()
                .header(REPLAY_HEADER, "true")
                .body(body);
    }

    private boolean isStale(IdempotencyRecord record, int staleSeconds) {
        if (record.getCreatedAt() == null) {
            return true;
        }
        return Duration.between(record.getCreatedAt(), LocalDateTime.now()).getSeconds() > staleSeconds;
    }

    private String hash(String endpoint, Object[] args) {
        StringBuilder sb = new StringBuilder(endpoint);
        for (Object arg : args) {
            if (arg == null || arg instanceof Authentication
                    || arg instanceof ServletRequest || arg instanceof ServletResponse) {
                continue;
            }
            sb.append('|').append(arg);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    private User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            return null;
        }
        return user;
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}