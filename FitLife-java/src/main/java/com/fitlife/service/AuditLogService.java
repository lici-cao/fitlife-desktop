package com.fitlife.service;

import com.fitlife.entity.AuditLog;
import com.fitlife.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审计日志写入。
 *
 * <p>审计失败绝不能影响主流程，因此内部吞掉异常并只打 warn；
 * 同时不记录业务正文（身高体重、伤病等），只留动作与资源 id。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository repository;

    public void log(Long userId, String username, String action, String target,
                    Long targetId, String detail, String ip) {
        try {
            repository.save(AuditLog.builder()
                    .userId(userId)
                    .username(username)
                    .action(action)
                    .target(target)
                    .targetId(targetId)
                    .detail(detail)
                    .ip(ip)
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("写审计日志失败（action={}, targetId={}）: {}", action, targetId, e.getMessage());
        }
    }
}