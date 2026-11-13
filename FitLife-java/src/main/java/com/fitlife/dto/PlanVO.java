package com.fitlife.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 训练计划完整响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanVO {
    private Long id;
    private String title;
    private String goal;
    private Integer weeklyDays;
    private Integer sessionMinutes;
    private String summary;
    private Integer weeklyCardioMin;
    private Double weeklyCalories;
    /** true = 大模型生成；false = 规则模板兜底 */
    private Boolean aiGenerated;
    /** 生成说明 / 降级原因 */
    private String note;
    private String status;
    private LocalDateTime createdAt;
    private List<PlanDayVO> days;

    // ===== P2 版本链与校验证据 =====

    /** 同用户内递增版本号 */
    private Integer version;
    /** 上一版计划 id */
    private Long parentId;
    /** 本版变更说明（修复动作清单 / 降级原因） */
    private String changeNote;
    /** LLM / LLM_REPAIRED / TEMPLATE */
    private String sourceLink;
    /** 规则校验报告（反序列化自 validation_report，解析失败为 null） */
    private ValidationReport validationReport;
    /** 风险提示（校验报告的便捷视图） */
    private List<String> riskNotes;
    /** 端到端生成耗时 ms */
    private Integer generationMs;
    /** 本次生成消耗 token 总数 */
    private Integer aiTokens;
}
