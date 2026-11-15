package com.fitlife.aspect;

import com.fitlife.common.BusinessException;
import com.fitlife.common.annotation.OwnershipCheck;
import com.fitlife.entity.User;
import com.fitlife.repository.CustomFoodRepository;
import com.fitlife.repository.PlanRepository;
import com.fitlife.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 数据归属切面（水平越权防护）。
 *
 * <p>在方法体执行之前就用一条 {@code existsByIdAndUserId} 查询判定归属，
 * 越权直接 403 并写审计日志——避免「先查出来、再在各个分支里判断」时漏判。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class OwnershipAspect {

    private final PlanRepository planRepository;
    private final CustomFoodRepository customFoodRepository;
    private final AuditLogService auditLogService;

    @Around("@annotation(com.fitlife.common.annotation.OwnershipCheck)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        OwnershipCheck check = signature.getMethod().getAnnotation(OwnershipCheck.class);

        User user = currentUser();
        if (user == null || user.getId() == null) {
            throw new BusinessException(401, "未登录或登录已过期");
        }

        Long resourceId = resolveId(pjp, check.idParam());
        if (resourceId == null) {
            throw new BusinessException(400, "缺少资源 ID");
        }

        boolean owned = switch (check.type()) {
            case PLAN -> planRepository.existsByIdAndUserId(resourceId, user.getId());
            case CUSTOM_FOOD -> customFoodRepository.existsByIdAndUserId(resourceId, user.getId());
        };

        if (!owned) {
            String action = check.type().name() + "_ACCESS_DENIED";
            auditLogService.log(user.getId(), user.getUsername(), action, check.type().name(),
                    resourceId, "归属校验失败：资源不属于当前用户", clientIp());
            log.warn("归属校验失败: user={}, type={}, id={}", user.getId(), check.type(), resourceId);
            throw new BusinessException(403, "无权访问该资源");
        }

        return pjp.proceed();
    }

    /**
     * 取资源 id：优先按注解声明的参数名匹配；
     * 方法参数名不可用（未开 -parameters）时退化为「第一个 Long 参数」。
     */
    private Long resolveId(ProceedingJoinPoint pjp, String idParam) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String[] names = signature.getParameterNames();
        Object[] args = pjp.getArgs();
        if (names != null) {
            for (int i = 0; i < names.length && i < args.length; i++) {
                if (idParam.equals(names[i]) && args[i] instanceof Long id) {
                    return id;
                }
            }
        }
        for (Object arg : args) {
            if (arg instanceof Long id) {
                return id;
            }
        }
        return null;
    }

    private User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            return null;
        }
        return user;
    }

    private String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        }
        return null;
    }
}