package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Entity
@Table(name = "user_profiles")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", unique = true, nullable = false)
    private Long userId;

    private String name;
    private String gender;
    private Integer age;
    private Double height;
    private Double weight;

    @Column(name = "body_fat")
    private Double bodyFat;

    private String goal;

    @Column(name = "activity_level")
    private String activityLevel;

    // ===== 计划生成所需的结构化画像字段 =====

    /** 运动经验：初级 / 中级 / 高级 */
    @Column(length = 20)
    private String experience;

    /** 每周可训练天数 */
    @Column(name = "weekly_days")
    private Integer weeklyDays;

    /** 单次训练时长（分钟） */
    @Column(name = "session_minutes")
    private Integer sessionMinutes;

    /** 可用器械，逗号分隔，如 "徒手,哑铃" */
    @Column(length = 120)
    private String equipments;

    /** 伤病史 / 禁忌部位，逗号分隔，如 "膝关节,腰椎" */
    @Column(length = 120)
    private String injuries;
}
