package com.fitlife.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 某一天的饮食安排。
 *
 * <p>热量与三大营养素都给出计划值与合计值，前端不需要再算，
 * 便于用户按「今天吃了 1850/2100 kcal」直接对照。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanDietDayVO {

    private Integer dayOfWeek;
    private String dayLabel;

    /** 当日热量合计 kcal */
    private Double kcalTotal;
    private Double proteinTotal;
    private Double carbTotal;
    private Double fatTotal;

    private List<PlanDietItemVO> items;
}