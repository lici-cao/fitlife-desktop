package com.fitlife.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一项饮食建议。所有数值都带单位标签，避免出现「吃 200 鸡胸」这种歧义。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanDietItemVO {

    /** 早餐 / 午餐 / 晚餐 / 加餐 */
    private String meal;

    private String foodName;

    /** 建议克数 */
    private Double grams;

    /** 计量单位符号：g / 个 / 碗 / 杯 */
    private String unitLabel;

    /** 份量描述，如「1 碗(150g)」 */
    private String amountDesc;

    private Double kcal;

    private Double proteinG;
    private Double carbG;
    private Double fatG;

    /** 该食物数据来源链接 */
    private String sourceUrl;

    /** 数据来源名称，如「中国食物成分表 第6版」 */
    private String sourceTitle;

    private Integer seq;
}