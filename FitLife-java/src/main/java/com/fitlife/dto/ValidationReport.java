package com.fitlife.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规则校验报告（P2）。
 *
 * <p>PlanValidator 六类校验的输出；序列化后存 plans.validation_report，
 * 前端在计划详情里折叠展示，答辩演示「生成过程受科学规则约束」的直接证据。
 */
@Data
public class ValidationReport {

    /** 无 ERROR 级违例即为 true（ERROR 会被修复器处理或导致降级） */
    private boolean passed;

    /** 100 − 15×ERROR − 5×WARN，下限 0 */
    private int score;

    private List<Check> checks = new ArrayList<>();

    /** 面向用户的风险提示（从违例与知识条目提炼） */
    private List<String> riskNotes = new ArrayList<>();

    /** 本次校验实际采用的阈值参数（来自 knowledge_rule，缺省为内置默认） */
    private Map<String, Object> paramsUsed;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Check {
        /** V1 靶心率 / V2 FITT / V3 进阶 / V4 恢复 / V5 禁忌 / V6 容量 */
        private String code;
        private String name;
        /** ERROR / WARN / INFO */
        private String level;
        private String detail;
    }

    public void add(String code, String name, String level, String detail) {
        checks.add(new Check(code, name, level, detail));
    }
}
