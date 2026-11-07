package com.fitlife.dto;

import lombok.Data;

@Data
public class ProfileDTO {
    private String name;
    private String gender;
    private Integer age;
    private Double height;
    private Double weight;
    private Double bodyFat;
    private String goal;
    private String activityLevel;

    /** 运动经验：初级 / 中级 / 高级 */
    private String experience;
    /** 每周可训练天数 */
    private Integer weeklyDays;
    /** 单次训练时长（分钟） */
    private Integer sessionMinutes;
    /** 可用器械，逗号分隔 */
    private String equipments;
    /** 伤病史 / 禁忌部位，逗号分隔 */
    private String injuries;
}
