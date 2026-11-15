package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 动作库投稿单（P5 内容治理）。
 *
 * <p>用户投稿不直接写 exercises，而是先落审核单：管理员核对「来源可访问 +
 * 与来源描述一致 + 领域参数合理」后，才由服务端生成 exercises 行并把
 * submission.created_exercise_id 指回去，形成可追溯的审核流水。
 */
@Entity
@Table(name = "exercise_submission")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExerciseSubmission {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 60)
    private String username;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 20)
    private String category;

    @Column(name = "muscle_group", nullable = false, length = 30)
    private String muscleGroup;

    @Column(nullable = false, length = 20)
    private String equipment;

    @Column(nullable = false)
    private Integer difficulty;

    @Column(name = "met_value", nullable = false)
    private Double metValue;

    @Column(name = "default_sets")
    private Integer defaultSets;

    @Column(name = "default_reps", length = 20)
    private String defaultReps;

    @Column(length = 255)
    private String description;

    @Column(length = 100)
    private String contraindication;

    @Column(name = "source_type", length = 24)
    private String sourceType;

    @Column(name = "source_title", length = 180)
    private String sourceTitle;

    @Column(name = "source_url", length = 255)
    private String sourceUrl;

    @Column(name = "source_page", length = 64)
    private String sourcePage;

    @Column(name = "evidence_level", length = 4)
    private String evidenceLevel;

    @Column(name = "license_note", length = 120)
    private String licenseNote;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = PENDING;

    @Column(name = "reviewer_id")
    private Long reviewerId;

    @Column(name = "reviewer_name", length = 60)
    private String reviewerName;

    @Column(name = "review_note", length = 255)
    private String reviewNote;

    @Column(name = "created_exercise_id")
    private Long createdExerciseId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = PENDING;
        }
    }
}