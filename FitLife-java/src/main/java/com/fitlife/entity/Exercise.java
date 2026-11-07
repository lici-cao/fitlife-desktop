package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 运动动作库（可检索的知识源，供 AI 编排训练计划时引用）
 */
@Entity
@Table(name = "exercises")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Exercise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    /** 力量 / 有氧 / 核心 / 柔韧 / HIIT */
    @Column(nullable = false, length = 20)
    private String category;

    /** 胸 / 背 / 肩 / 手臂 / 腿 / 臀 / 核心 / 全身 */
    @Column(name = "muscle_group", nullable = false, length = 30)
    private String muscleGroup;

    /** 徒手 / 哑铃 / 杠铃 / 器械 / 弹力带 / 壶铃 */
    @Column(nullable = false, length = 20)
    private String equipment;

    /** 1 初级 2 中级 3 高级 */
    @Column(nullable = false)
    private Integer difficulty;

    /** 代谢当量，用于估算热量消耗 */
    @Column(name = "met_value", nullable = false)
    private Double metValue;

    @Column(name = "default_sets")
    private Integer defaultSets;

    @Column(name = "default_reps", length = 20)
    private String defaultReps;

    @Column(length = 255)
    private String description;

    /** 禁忌症人群，逗号分隔，如 "膝关节,腰椎"；空表示无禁忌 */
    @Column(length = 100)
    private String contraindication;
}
