package com.fitlife.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 身体指标计算器。
 *
 * <p>把用户档案里的身高 / 体重 / 年龄 / 性别 / 活动量，换算成 BMI、BMR、TDEE、
 * 体脂率估算、目标热量与蛋白质区间。计算类问题（体脂率怎么算、每天该吃多少）
 * 由后端先算好再注入提示词，大模型只负责解释与给建议，避免模型自由发挥时
 * 出现「你问 A 它答 B」的含糊回答。</p>
 */
public final class BodyMetrics {

    private BodyMetrics() {
    }

    /**
     * 计算类问题关键词（体脂率 / 热量 / 蛋白质…）。
     * 命中时不注入动作库，避免模型被带偏成「给你排一套训练」——也就是答非所问。
     */
    public static final java.util.regex.Pattern CALC = java.util.regex.Pattern.compile(
            "体脂|体成分|BMI|bmi|基础代谢|BMR|TDEE|热量|卡路里|大卡|蛋白质|碳水|脂肪摄入|吃多少|怎么算|计算公式");

    public record Metrics(
            double weight, double height, int age, boolean male,
            double bmi, String bmiLabel,
            double bmr, double tdee,
            double bodyFat, boolean bodyFatMeasured, double bodyFatEstimate,
            double targetCalories, String goal,
            double proteinLow, double proteinHigh) {
    }

    /** 无法计算（缺少身高或体重）时返回 null */
    public static Metrics from(Map<String, Object> context) {
        Map<String, Object> p = profile(context);
        if (p == null) {
            return null;
        }
        double weight = num(p.get("weight"), 0);
        double height = num(p.get("height"), 0);
        if (weight <= 0 || height <= 0) {
            return null;
        }
        int age = (int) Math.round(num(p.get("age"), 25));
        if (age <= 0 || age > 120) {
            age = 25;
        }
        boolean male = isMale(str(p.get("gender")));

        double bmi = weight / Math.pow(height / 100.0, 2);
        double bmr = 10 * weight + 6.25 * height - 5 * age + (male ? 5 : -161);
        double factor = activityFactor(str(p.get("activityLevel")));
        double tdee = bmr * factor;

        // Deurenberg 公式：体脂率 = 1.2×BMI + 0.23×年龄 − 10.8×性别 − 5.4（男 1 / 女 0）
        double bfEstimate = 1.2 * bmi + 0.23 * age - (male ? 10.8 : 0) - 5.4;
        double bfInput = num(p.get("bodyFat"), 0);
        boolean measured = bfInput > 0;

        String goal = str(p.get("goal"));
        double target = tdee;
        double proteinLow = 1.6;
        double proteinHigh = 2.0;
        if (goal.toLowerCase(Locale.ROOT).contains("fat") || goal.contains("减脂")) {
            target = tdee - 400;
            proteinLow = 1.8;
            proteinHigh = 2.2;
        } else if (goal.toLowerCase(Locale.ROOT).contains("muscle")
                || goal.toLowerCase(Locale.ROOT).contains("gain")
                || goal.contains("增肌")) {
            target = tdee + 300;
        }

        return new Metrics(
                round(weight, 1), round(height, 1), age, male,
                round(bmi, 1), bmiLabel(bmi),
                Math.round(bmr), Math.round(tdee),
                round(measured ? bfInput : bfEstimate, 1), measured, round(bfEstimate, 1),
                Math.round(target), goal.isBlank() ? "未设置" : goal,
                Math.round(weight * proteinLow), Math.round(weight * proteinHigh));
    }

    /** 生成可直接塞进提示词的指标块 */
    public static String describe(Metrics m) {
        if (m == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n【已算好的身体指标（直接引用这些数值，不要自己重新计算）】\n")
                .append("- 身高 / 体重：").append(fmt(m.height())).append(" cm / ")
                .append(fmt(m.weight())).append(" kg，BMI ").append(fmt(m.bmi()))
                .append("（").append(m.bmiLabel()).append("）\n")
                .append("- 基础代谢 BMR：").append(Math.round(m.bmr()))
                .append(" kcal（Mifflin-St Jeor 公式）\n")
                .append("- 每日总消耗 TDEE：").append(Math.round(m.tdee())).append(" kcal\n")
                .append("- 体脂率：").append(fmt(m.bodyFat()))
                .append(m.bodyFatMeasured() ? "%（用户档案里的实测值）" : "%（Deurenberg 公式估算）");
        if (m.bodyFatMeasured()) {
            sb.append("，公式估算值为 ").append(fmt(m.bodyFatEstimate())).append("%");
        }
        sb.append("\n")
                .append("- 目标热量：").append(Math.round(m.targetCalories()))
                .append(" kcal/天（目标：").append(m.goal()).append("）\n")
                .append("- 蛋白质建议：").append(Math.round(m.proteinLow())).append("–")
                .append(Math.round(m.proteinHigh())).append(" g/天\n")
                .append("- 性别 / 年龄：").append(m.male() ? "男" : "女").append(" / ").append(m.age()).append(" 岁\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 内部工具
    /** 面向用户的指标卡（模型不可用时也能给出确定数字，而不是空泛话术） */
    public static String toUserCard(Metrics m) {
        if (m == null) {
            return "";
        }
        return "### 按你的档案算出来的指标\n\n"
                + "| 指标 | 数值 | 说明 |\n| --- | --- | --- |\n"
                + "| BMI | " + fmt(m.bmi()) + " | " + m.bmiLabel() + " |\n"
                + "| 基础代谢 BMR | " + Math.round(m.bmr()) + " kcal | Mifflin-St Jeor 公式 |\n"
                + "| 每日总消耗 TDEE | " + Math.round(m.tdee()) + " kcal | 含日常活动量 |\n"
                + "| 体脂率 | " + fmt(m.bodyFat()) + "% | "
                + (m.bodyFatMeasured() ? "档案实测值" : "Deurenberg 公式估算") + " |\n"
                + "| 目标热量 | " + Math.round(m.targetCalories()) + " kcal/天 | 目标：" + m.goal() + " |\n"
                + "| 蛋白质 | " + Math.round(m.proteinLow()) + "–" + Math.round(m.proteinHigh())
                + " g/天 | " + m.weight() + " kg 体重换算 |\n";
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> profile(Map<String, Object> context) {
        if (context == null) {
            return null;
        }
        Object raw = context.get("userProfile");
        if (raw instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        // 兼容直接把字段平铺在 context 里的调用
        return context;
    }

    private static double activityFactor(String level) {
        if (level == null) {
            return 1.4;
        }
        return switch (level.toLowerCase(Locale.ROOT)) {
            case "sedentary", "久坐" -> 1.2;
            case "light", "轻度" -> 1.375;
            case "moderate", "中等" -> 1.55;
            case "active", "高" -> 1.725;
            case "very_active", "veryactive", "极高" -> 1.9;
            default -> 1.4;
        };
    }

    private static String bmiLabel(double bmi) {
        if (bmi < 18.5) {
            return "偏瘦";
        }
        if (bmi < 24) {
            return "正常";
        }
        if (bmi < 28) {
            return "超重";
        }
        return "肥胖";
    }

    private static boolean isMale(String gender) {
        if (gender == null) {
            return false;
        }
        String g = gender.toLowerCase(Locale.ROOT);
        return g.startsWith("m") || gender.contains("男") || "1".equals(g);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static double num(Object o, double fallback) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            String s = str(o).replaceAll("[^0-9.\\-]", "");
            return s.isEmpty() ? fallback : Double.parseDouble(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double round(double v, int digits) {
        double p = Math.pow(10, digits);
        return Math.round(v * p) / p;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
