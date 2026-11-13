package com.fitlife.service;

import com.fitlife.dto.PlanVO;
import com.fitlife.entity.ChatMessage;
import com.fitlife.entity.ChatSession;
import com.fitlife.entity.Plan;
import com.fitlife.entity.UserProfile;
import com.fitlife.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * P3：会话与消息服务端持久化。
 * 设计约束：落库失败绝不阻断对话主流程（全部 try/catch warn）；
 * 写方法用 REQUIRES_NEW，避免被业务事务回滚连带丢失留痕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    /** 上下文装配时回看的最多轮数（一轮 = user + assistant 两条） */
    private static final int CONTEXT_TURNS = 6;

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final PlanRepository planRepository;
    private final PlanDetailRepository planDetailRepository;
    private final UserProfileRepository userProfileRepository;
    private final ExerciseRepository exerciseRepository;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** 按 key 找会话；没有就新建（title 截自首条消息）。返回 null 表示持久化不可用（不阻断）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChatSession findOrCreateSession(Long userId, String sessionKey, String firstMessage) {
        try {
            ChatSession s = null;
            if (sessionKey != null && !sessionKey.isBlank()) {
                s = chatSessionRepository.findBySessionKey(sessionKey.trim()).orElse(null);
            }
            if (s == null) {
                String title = firstMessage == null ? "新会话"
                        : (firstMessage.length() > 40 ? firstMessage.substring(0, 40) + "…" : firstMessage);
                s = ChatSession.builder()
                        .sessionKey(sessionKey == null || sessionKey.isBlank() ? null : sessionKey.trim())
                        .userId(userId)
                        .title(title)
                        .build();
                s = chatSessionRepository.save(s);
            }
            return s;
        } catch (Exception e) {
            log.warn("会话持久化不可用：{}", e.getMessage());
            return null;
        }
    }

    /** 兼容前端把会话自增 id 当字符串传回的情况。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChatSession resolveSession(Long userId, String sessionKeyOrId, String firstMessage) {
        try {
            if (sessionKeyOrId != null && isNumeric(sessionKeyOrId.trim())) {
                Long id = Long.parseLong(sessionKeyOrId.trim());
                Optional<ChatSession> s = chatSessionRepository.findById(id);
                if (s.isPresent() && s.get().getUserId().equals(userId)) {
                    return s.get();
                }
            }
            return findOrCreateSession(userId, sessionKeyOrId, firstMessage);
        } catch (Exception e) {
            log.warn("会话解析失败：{}", e.getMessage());
            return null;
        }
    }

    /** 追加一条消息（action/refs 可空）。失败仅告警。
     *  注意：本方法可能被外层业务事务调用，此处用 TransactionTemplate 显式开独立事务，
     *  避免自调用绕过 @Transactional(REQUIRES_NEW) 代理导致留痕被业务回滚连带丢失。 */
    public ChatMessage appendMessage(ChatSession session, String role, String content,
                                     String action, String refs) {
        if (session == null) return null;
        try {
            final String body = content == null ? null
                    : (content.length() > 60000 ? content.substring(0, 60000) : content);
            final String refsv = refs == null ? null
                    : (refs.length() > 500 ? refs.substring(0, 500) : refs);
            return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .execute(tx -> {
                        ChatMessage m = ChatMessage.builder()
                                .sessionId(session.getId())
                                .role(role)
                                .content(body)
                                .action(action)
                                .refs(refsv)
                                .build();
                        return chatMessageRepository.save(m);
                    });
        } catch (Exception e) {
            log.warn("消息落库失败：{}", e.getMessage());
            return null;
        }
    }

    /** 更新会话活跃时间与聚焦计划。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touchSession(ChatSession session, Long planId) {
        try {
            if (session == null) return;
            session.setLastActiveAt(java.time.LocalDateTime.now());
            if (planId != null) session.setPlanId(planId);
            chatSessionRepository.save(session);
        } catch (Exception e) {
            log.warn("会话 touch 失败：{}", e.getMessage());
        }
    }

    /**
     * 上下文装配（图 3 第 3 步）：最近 N 轮对话摘要 + 当前 ACTIVE 计划（版本/来源/结构）+
     * 体质档案要点 + 上次校验报告。返回注入 Prompt 的纯文本；持久化不可用时退化为档案+计划。
     */
    public String assembleContext(Long userId, ChatSession session) {
        StringBuilder sb = new StringBuilder();
        // 1) 体质档案
        try {
            Optional<UserProfile> p = userProfileRepository.findByUserId(userId);
            p.ifPresent(prof -> sb.append("【用户档案】")
                    .append(prof.getGender()).append(" / ").append(prof.getAge()).append("岁 / ")
                    .append(prof.getHeight()).append("cm / ").append(prof.getWeight()).append("kg / 目标=")
                    .append(prof.getGoal()).append(" / 经验=").append(prof.getExperience())
                    .append(" / 器械=").append(prof.getEquipments())
                    .append(" / 伤病=").append(prof.getInjuries() == null ? "无" : prof.getInjuries())
                    .append('\n'));
        } catch (Exception ignored) {
        }
        // 2) 当前 ACTIVE 计划
        try {
            Optional<Plan> active = planRepository.findFirstByUserIdAndStatusOrderByIdDesc(userId, "ACTIVE");
            if (active.isPresent()) {
                PlanVO vo = PlanSupport.toVO(active.get(),
                        planDetailRepository.findByPlanIdOrderByDayOfWeekAscSeqAsc(active.get().getId()),
                        exerciseRepository, objectMapper);
                sb.append("【当前计划 v").append(vo.getVersion()).append("（")
                        .append(vo.getSourceLink() == null ? "LLM" : vo.getSourceLink()).append("）】")
                        .append(vo.getTitle()).append("，每周").append(vo.getWeeklyDays())
                        .append("练，每次").append(vo.getSessionMinutes()).append("分钟。")
                        .append('\n');
                // 训练日结构（供修订定位）
                if (vo.getDays() != null) {
                    for (var d : vo.getDays()) {
                        if (Boolean.TRUE.equals(d.getRestDay()) || d.getItems() == null || d.getItems().isEmpty()) {
                            continue;
                        }
                        StringBuilder names = new StringBuilder();
                        for (var it : d.getItems()) {
                            if (names.length() > 0) names.append("、");
                            names.append(it.getExerciseName()).append("(id=").append(it.getExerciseId())
                                    .append(",rpe=").append(it.getRpe()).append(")");
                        }
                        sb.append("  ").append(d.getDayLabel()).append(" ").append(d.getFocus())
                                .append("：").append(names).append('\n');
                    }
                }
                // 3) 上次校验报告要点
                if (vo.getValidationReport() != null) {
                    var rep = vo.getValidationReport();
                    sb.append("【上次校验】score=").append(rep.getScore())
                            .append(" passed=").append(rep.isPassed());
                    if (rep.getChecks() != null && !rep.getChecks().isEmpty()) {
                        sb.append("，未通过项：");
                        int n = 0;
                        for (var c : rep.getChecks()) {
                            if (!"PASS".equalsIgnoreCase(c.getLevel()) && n++ < 4) {
                                sb.append('[').append(c.getCode()).append(' ').append(c.getLevel()).append("] ");
                            }
                        }
                    }
                    sb.append('\n');
                }
            } else {
                sb.append("【当前计划】暂无生效计划\n");
            }
        } catch (Exception e) {
            log.warn("上下文装配-计划段失败：{}", e.getMessage());
        }
        // 4) 最近 N 轮对话
        if (session != null) {
            try {
                List<ChatMessage> recent = chatMessageRepository
                        .findBySessionIdOrderByIdAsc(session.getId(),
                                org.springframework.data.domain.PageRequest.of(0, 500))
                        .getContent();
                int from = Math.max(0, recent.size() - CONTEXT_TURNS * 2);
                if (from < recent.size()) {
                    sb.append("【最近对话】\n");
                    for (int i = from; i < recent.size(); i++) {
                        ChatMessage m = recent.get(i);
                        if (m.getContent() == null || m.getContent().isBlank()) continue;
                        String brief = m.getContent().length() > 120
                                ? m.getContent().substring(0, 120) + "…" : m.getContent();
                        sb.append("  ").append(m.getRole()).append("：")
                                .append(brief.replace('\n', ' ')).append('\n');
                    }
                }
            } catch (Exception e) {
                log.warn("上下文装配-历史段失败：{}", e.getMessage());
            }
        }
        return sb.toString();
    }

    public List<ChatMessage> historyOf(ChatSession session, int maxMessages) {
        if (session == null) return List.of();
        try {
            return chatMessageRepository
                    .findBySessionIdOrderByIdAsc(session.getId(),
                            org.springframework.data.domain.PageRequest.of(0, maxMessages))
                    .getContent();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (char c : s.toCharArray()) {
            if (!Character.isDigit(c)) return false;
        }
        return true;
    }
}
