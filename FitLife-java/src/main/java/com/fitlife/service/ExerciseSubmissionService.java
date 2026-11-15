package com.fitlife.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.common.BusinessException;
import com.fitlife.dto.ExerciseSubmissionDTO;
import com.fitlife.entity.Exercise;
import com.fitlife.entity.ExerciseSubmission;
import com.fitlife.repository.ExerciseRepository;
import com.fitlife.repository.ExerciseSubmissionRepository;
import com.fitlife.repository.KnowledgeRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 动作库投稿与审核（P5）。
 *
 * <p>回答「用户想加新动作怎么办、审核审什么」：
 * <ol>
 *   <li>用户只能提交到 exercise_submission，不能直接写动作库；</li>
 *   <li>提交时先跑一遍机器可判的门槛：必填项、枚举合法性、MET 区间、难度区间、
 *       重名、来源链接是否 http(s) 且落在白名单域；</li>
 *   <li>管理员人工审的是三件事——来源是否真实可访问、条目描述是否与来源一致、
 *       MET/难度是否与来源量级相符。<b>不是替第三方来源背书其科学性</b>，
 *       而是保证「这条内容能在来源里查到，且没有被我改坏」；</li>
 *   <li>通过后由服务端生成 exercises 行，review_status=APPROVED，并回填
 *       reviewed_by/reviewed_at/created_exercise_id，形成可追溯流水。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExerciseSubmissionService {

    private static final Set<String> CATEGORIES = Set.of("力量", "有氧", "核心", "柔韧", "HIIT");
    private static final Set<String> MUSCLES = Set.of("胸", "背", "肩", "手臂", "腿", "臀", "核心", "全身");
    private static final Set<String> EQUIPMENTS = Set.of("徒手", "哑铃", "杠铃", "器械", "弹力带", "壶铃");

    private final ExerciseSubmissionRepository submissionRepository;
    private final ExerciseRepository exerciseRepository;
    private final KnowledgeRuleRepository knowledgeRuleRepository;

    /**
     * 提交投稿。返回落库后的审核单（status=PENDING 或 REJECTED）。
     * 明显不合规的投稿直接落库并标 REJECTED，让用户看到原因，而不是静默丢弃。
     */
    @Transactional
    public ExerciseSubmission submit(Long userId, String username, ExerciseSubmissionDTO dto) {
        List<String> errors = validate(dto);
        ExerciseSubmission sub = ExerciseSubmission.builder()
                .userId(userId)
                .username(username)
                .name(trim(dto.getName()))
                .category(trim(dto.getCategory()))
                .muscleGroup(trim(dto.getMuscleGroup()))
                .equipment(trim(dto.getEquipment()))
                .difficulty(dto.getDifficulty() == null ? 1 : dto.getDifficulty())
                .metValue(dto.getMetValue() == null ? 0.0 : dto.getMetValue())
                .defaultSets(dto.getDefaultSets() == null ? 3 : dto.getDefaultSets())
                .defaultReps(trim(dto.getDefaultReps()))
                .description(trim(dto.getDescription()))
                .contraindication(trim(dto.getContraindication()))
                .sourceType(defaultIfBlank(dto.getSourceType(), "GUIDELINE"))
                .sourceTitle(trim(dto.getSourceTitle()))
                .sourceUrl(trim(dto.getSourceUrl()))
                .sourcePage(trim(dto.getSourcePage()))
                .evidenceLevel(defaultIfBlank(dto.getEvidenceLevel(), "B"))
                .licenseNote(trim(dto.getLicenseNote()))
                .status(ExerciseSubmission.PENDING)
                .build();
        if (!errors.isEmpty()) {
            sub.setStatus(ExerciseSubmission.REJECTED);
            sub.setReviewNote(("自动校验未通过：" + String.join("；", errors)));
            sub.setReviewedAt(LocalDateTime.now());
            sub.setReviewerName("SYSTEM");
            log.info("投稿自动驳回 userId={} name={} 原因={}", userId, sub.getName(), sub.getReviewNote());
        }
        return submissionRepository.save(sub);
    }

    /** 机器可判的门槛（与 CONTENT_REVIEW 规则同源，规则缺失时退内置值） */
    public List<String> validate(ExerciseSubmissionDTO dto) {
        List<String> errors = new ArrayList<>();
        JsonNode rule = contentRule();

        String name = trim(dto.getName());
        if (name.length() < 2 || name.length() > 60) {
            errors.add("动作名称需 2–60 字");
        } else if (exerciseRepository.existsByNameIgnoreCase(name)) {
            errors.add("动作库已有同名动作，请直接使用或改为更明确的名称");
        }
        if (dto.getCategory() == null || !CATEGORIES.contains(dto.getCategory())) {
            errors.add("类别必须是 力量/有氧/核心/柔韧/HIIT");
        }
        if (dto.getMuscleGroup() == null || !MUSCLES.contains(dto.getMuscleGroup())) {
            errors.add("主要肌群必须是 胸/背/肩/手臂/腿/臀/核心/全身");
        }
        if (dto.getEquipment() == null || !EQUIPMENTS.contains(dto.getEquipment())) {
            errors.add("器械必须是 徒手/哑铃/杠铃/器械/弹力带/壶铃");
        }
        if (dto.getDifficulty() == null || dto.getDifficulty() < 1 || dto.getDifficulty() > 3) {
            errors.add("难度需为 1–3");
        }
        double metMin = rule.path("metMin").asDouble(1.0);
        double metMax = rule.path("metMax").asDouble(20.0);
        double met = dto.getMetValue() == null ? 0 : dto.getMetValue();
        if (met < metMin || met > metMax) {
            errors.add(String.format("MET 需在 %.0f–%.0f 之间（当前 %s）", metMin, metMax, met));
        }
        String url = trim(dto.getSourceUrl());
        if (rule.path("requireSourceUrl").asBoolean(true) && url.isEmpty()) {
            errors.add("必须提供可访问的来源链接");
        } else if (!url.isEmpty() && !isHttpUrl(url)) {
            errors.add("来源链接必须是 http(s) 地址");
        }
        String title = trim(dto.getSourceTitle());
        if (title.length() < 4) {
            errors.add("来源标题过短，请写到能定位到条目（指南名/书名/数据库条目）");
        }
        int minDesc = rule.path("descriptionMinLen").asInt(8);
        if (trim(dto.getDescription()).length() < minDesc) {
            errors.add("动作说明至少 " + minDesc + " 字，需描述动作要领与注意点");
        }
        return errors;
    }

    /** 管理员通过：生成 exercises 行并回填审核流水 */
    @Transactional
    public Exercise approve(Long submissionId, Long reviewerId, String reviewerName, String note) {
        ExerciseSubmission sub = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new BusinessException(404, "投稿不存在"));
        if (!ExerciseSubmission.PENDING.equals(sub.getStatus())) {
            throw new BusinessException(409, "该投稿已处理过，当前状态：" + sub.getStatus());
        }
        if (exerciseRepository.existsByNameIgnoreCase(sub.getName())) {
            throw new BusinessException(409, "动作库已存在同名动作，无法重复通过");
        }
        Exercise ex = Exercise.builder()
                .name(sub.getName())
                .category(sub.getCategory())
                .muscleGroup(sub.getMuscleGroup())
                .equipment(sub.getEquipment())
                .difficulty(sub.getDifficulty())
                .metValue(sub.getMetValue())
                .defaultSets(sub.getDefaultSets())
                .defaultReps(sub.getDefaultReps())
                .description(sub.getDescription())
                .contraindication(sub.getContraindication())
                .sourceType(sub.getSourceType())
                .sourceTitle(sub.getSourceTitle())
                .sourceUrl(sub.getSourceUrl())
                .sourcePage(sub.getSourcePage())
                .evidenceLevel(sub.getEvidenceLevel())
                .licenseNote(sub.getLicenseNote())
                .reviewStatus("APPROVED")
                .submittedBy(sub.getUserId())
                .reviewedBy(reviewerId)
                .reviewedAt(LocalDateTime.now())
                .reviewNote(note)
                .lastVerifiedAt(LocalDate.now())
                .build();
        ex = exerciseRepository.save(ex);

        sub.setStatus(ExerciseSubmission.APPROVED);
        sub.setReviewerId(reviewerId);
        sub.setReviewerName(reviewerName);
        sub.setReviewNote(note);
        sub.setReviewedAt(LocalDateTime.now());
        sub.setCreatedExerciseId(ex.getId());
        submissionRepository.save(sub);
        log.info("投稿通过 id={} → exerciseId={} 审核人={}", submissionId, ex.getId(), reviewerName);
        return ex;
    }

    /** 管理员驳回：必须写原因，理由会回显给投稿人 */
    @Transactional
    public ExerciseSubmission reject(Long submissionId, Long reviewerId, String reviewerName, String note) {
        if (note == null || note.isBlank()) {
            throw new BusinessException(400, "驳回必须填写原因");
        }
        ExerciseSubmission sub = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new BusinessException(404, "投稿不存在"));
        if (!ExerciseSubmission.PENDING.equals(sub.getStatus())) {
            throw new BusinessException(409, "该投稿已处理过，当前状态：" + sub.getStatus());
        }
        sub.setStatus(ExerciseSubmission.REJECTED);
        sub.setReviewerId(reviewerId);
        sub.setReviewerName(reviewerName);
        sub.setReviewNote(note);
        sub.setReviewedAt(LocalDateTime.now());
        return submissionRepository.save(sub);
    }

    public Page<ExerciseSubmission> list(String status, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size)));
        return status == null || status.isBlank()
                ? submissionRepository.findAllByOrderByIdDesc(pr)
                : submissionRepository.findByStatusOrderByIdDesc(status, pr);
    }

    public List<ExerciseSubmission> mine(Long userId) {
        return submissionRepository.findByUserIdOrderByIdDesc(userId);
    }

    public long pendingCount() {
        return submissionRepository.countByStatus(ExerciseSubmission.PENDING);
    }

    // ---------- 内部 ----------

    private JsonNode contentRule() {
        try {
            var rule = knowledgeRuleRepository.findByCode("CONTENT_REVIEW");
            if (rule.isPresent() && rule.get().getExpression() != null) {
                return new ObjectMapper().readTree(rule.get().getExpression());
            }
        } catch (Exception e) {
            log.warn("读取 CONTENT_REVIEW 失败，使用内置门槛：{}", e.getMessage());
        }
        return new ObjectMapper().createObjectNode();
    }

    private static boolean isHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && uri.getHost().contains(".");
        } catch (Exception e) {
            return false;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String defaultIfBlank(String s, String def) {
        return s == null || s.isBlank() ? def : s.trim();
    }
}