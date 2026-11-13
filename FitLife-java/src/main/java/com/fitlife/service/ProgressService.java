package com.fitlife.service;

import com.fitlife.dto.ApiResponse;
import com.fitlife.dto.PlanVO;
import com.fitlife.dto.ProgressAdviceVO;
import com.fitlife.dto.StatsSummaryVO;
import com.fitlife.entity.Plan;
import com.fitlife.entity.UserProfile;
import com.fitlife.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 反馈与调整决策（P4 图 4 ⑤⑥）。
 *
 * <p>只做"判断 + 给建议"，不擅自改用户的计划：决策给出理由与建议动作，
 * 真正的重新生成仍走 P2 编排器（会重新过校验器，把周容量增幅卡在 10% 以内），
 * 因此"自动进阶"不会绕过规则约束。
 *
 * <p>决策优先级：STOP（疼痛）> DELOAD（依从率过低）> SWAP（强度明显偏低）>
 * PROGRESS（依从率高且强度合适）> HOLD（其余情况）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProgressService {

    public static final String DECISION_HOLD = "HOLD";
    public static final String DECISION_DELOAD = "DELOAD";
    public static final String DECISION_PROGRESS = "PROGRESS";
    public static final String DECISION_SWAP = "SWAP";
    public static final String DECISION_STOP = "STOP";

    private final StatsService statsService;
    private final UserProfileRepository userProfileRepository;
    private final PlanService planService;

    /**
     * 计算调整决策。
     *
     * @param days 观察窗口（默认 14 天），太短的窗口会因样板不足而只给 HOLD
     */
    @Transactional
    public ProgressAdviceVO advice(Long userId, Integer days) {
        int window = days == null ? 14 : Math.max(7, Math.min(60, days));
        StatsSummaryVO stats = statsService.summary(userId, null, window);

        Map<String, Object> cfg = statsService.adviceConfig();
        double low = num(cfg, "adherenceLow", 70.0);
        double high = num(cfg, "adherenceHigh", 90.0);
        double rpeOver = num(cfg, "rpeOver", 1.0);
        double rpeUnder = num(cfg, "rpeUnder", -1.5);
        int minSessions = (int) num(cfg, "minSessionsForAdvice", 2);
        boolean painBlocks = !Boolean.FALSE.equals(cfg.get("painBlockAdvance"));

        int adherence = stats.getAdherenceRate() == null ? 0 : stats.getAdherenceRate();
        Double rpeDev = stats.getRpeDeviation();
        int painDays = stats.getPainDays() == null ? 0 : stats.getPainDays();
        int planned = stats.getPlannedItems() == null ? 0 : stats.getPlannedItems();
        int done = stats.getDoneItems() == null ? 0 : stats.getDoneItems();

        ProgressAdviceVO vo = new ProgressAdviceVO();
        vo.setWindowDays(window);
        vo.setAdherenceRate(adherence);
        vo.setRpeDeviation(rpeDev);
        vo.setPainDays(painDays);
        vo.setPlannedItems(planned);
        vo.setDoneItems(done);
        vo.setVolumeChangePct(10);
        vo.setCanAutoApply(false);

        List<String> reasons = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        Optional<Plan> active = planService.currentPlan(userId);
        active.ifPresent(p -> {
            vo.setBasePlanId(p.getId());
            vo.setBaseVersion(p.getVersion());
        });

        // ---------- 数据不足 ----------
        if (planned < minSessions) {
            vo.setDecision(DECISION_HOLD);
            vo.setTitle("样本不足，暂时保持当前安排");
            reasons.add("近 " + window + " 天只有 " + planned + " 个计划动作，还不足以判断趋势");
            suggestions.add("先按现有计划执行满 2 个训练日，再看这里的建议");
            vo.setRiskLevel(riskLevelOf(adherence, rpeDev, painDays, low));
            vo.setReasons(reasons);
            vo.setSuggestions(suggestions);
            vo.setNote("生成计划后打卡，系统据此给出加量/降载建议");
            return vo;
        }

        // ---------- 疼痛：最高优先级 ----------
        if (painDays > 0 && painBlocks) {
            vo.setDecision(DECISION_STOP);
            vo.setTitle("出现疼痛上报，先停止进阶");
            reasons.add("近 " + window + " 天有 " + painDays + " 天上报疼痛/不适");
            suggestions.add("暂停对应部位的力量动作，改为低强度有氧或休息");
            suggestions.add("疼痛持续或加重时先就医评估，恢复后再从上一版本的 70% 强度开始");
            if (adherence >= low) {
                suggestions.add("其余部位的可继续按当前计划执行");
            }
            vo.setRiskLevel("DANGER");
            vo.setReasons(reasons);
            vo.setSuggestions(suggestions);
            vo.setNote("疼痛状态下不加量，也不建议用换动作掩盖问题");
            return vo;
        }

        // ---------- 依从率过低：降载 ----------
        if (adherence < low) {
            vo.setDecision(DECISION_DELOAD);
            vo.setTitle("依从率偏低，建议降载而不是继续加量");
            reasons.add("近 " + window + " 天完成 " + done + "/" + planned + " 个动作，依从率 " + fmt(adherence) + "%");
            suggestions.add("每周次数减少 1 天，或把单次时长压缩到 30 分钟以内");
            suggestions.add("把最难的那一天拆成两个短训，降低启动门槛");
            vo.setRiskLevel("WARN");
            vo.setReasons(reasons);
            vo.setSuggestions(suggestions);
            vo.setNote("降载后可随时用历史版本回滚，不会丢数据");
            return vo;
        }

        // ---------- 强度明显偏低：换动作/加重量 ----------
        if (rpeDev != null && rpeDev < rpeUnder) {
            vo.setDecision(DECISION_SWAP);
            vo.setTitle("实际强度明显低于计划，建议替换偏轻的动作");
            reasons.add("实际 RPE 比计划低 " + fmt(Math.abs(rpeDev)) + " 个等级");
            suggestions.add("把自重动作换成同肌群的负重版本（如深蹲→负重深蹲）");
            suggestions.add("或提高组数/缩短组间休息，把 RPE 拉回计划区间");
            if (adherence >= high) {
                suggestions.add("依从率已达 " + fmt(adherence) + "%，可同时把每周总量提高不超过 10%");
            }
            vo.setRiskLevel("WARN");
            vo.setReasons(reasons);
            vo.setSuggestions(suggestions);
            vo.setNote("换动作仍会经过禁忌与容量校验");
            return vo;
        }

        // ---------- 强度过高：降强度 ----------
        if (rpeDev != null && rpeDev > rpeOver) {
            vo.setDecision(DECISION_DELOAD);
            vo.setTitle("实际强度高于计划，建议先降强度");
            reasons.add("实际 RPE 比计划高 " + fmt(rpeDev) + " 个等级");
            suggestions.add("主要动作的组数或重量下调 10%，把 RPE 拉回计划区间");
            vo.setRiskLevel("WARN");
            vo.setReasons(reasons);
            vo.setSuggestions(suggestions);
            vo.setNote("高强度叠加低恢复容易受伤，降到区间内再谈进阶");
            return vo;
        }

        // ---------- 达标：可进阶 ----------
        if (adherence >= high) {
            vo.setDecision(DECISION_PROGRESS);
            vo.setTitle("执行达标，可以小幅进阶");
            reasons.add("近 " + window + " 天依从率 " + fmt(adherence) + "%，达到 " + fmt(high) + "% 的进阶门槛");
            if (rpeDev != null) {
                reasons.add("实际强度与计划相差 " + fmt(rpeDev) + " 个 RPE 等级，仍在可推进范围");
            }
            suggestions.add("下一版把总量提高不超过 10%（如每周多 1 组主要动作，或单次加 5 分钟）");
            suggestions.add("进阶后若连续两天出现明显酸痛，回到上一版本");
            vo.setCanAutoApply(true);
            vo.setRiskLevel("INFO");
            vo.setReasons(reasons);
            vo.setSuggestions(suggestions);
            vo.setNote("点「进阶」会生成 v" + (vo.getBaseVersion() == null ? "n+1" : vo.getBaseVersion() + 1)
                    + " 计划，并重新过靶心率/容量/恢复校验");
            return vo;
        }

        // ---------- 其余：保持 ----------
        vo.setDecision(DECISION_HOLD);
        vo.setTitle("保持当前安排，先把依从率做稳");
        reasons.add("近 " + window + " 天依从率 " + fmt(adherence) + "%，处于 " + fmt(low) + "%–" + fmt(high) + "% 的维持区间");
        suggestions.add("再执行 1–2 周、依从率超过 " + fmt(high) + "% 后再考虑加量");
        vo.setRiskLevel("INFO");
        vo.setReasons(reasons);
        vo.setSuggestions(suggestions);
        vo.setNote("维持期的目标是稳定，不是追求更快进步");
        return vo;
    }

    /** 一键进阶：委托 P2 编排器重新生成（强制新版本），并把决策写进变更说明 */
    @Transactional
    public ProgressAdviceVO advance(Long userId, Integer days) {
        ProgressAdviceVO vo = advice(userId, days);
        if (!Boolean.TRUE.equals(vo.getCanAutoApply())) {
            vo.setNote("当前决策为 " + vo.getDecision() + "，不满足自动进阶条件；" + vo.getNote());
            return vo;
        }
        ApiResponse resp = planService.generateNextVersion(userId,
                "按执行反馈进阶：依从率 " + fmt(vo.getAdherenceRate()) + "%，总量增幅 ≤10%");
        if (resp != null && resp.getData() instanceof PlanVO next) {
            vo.setNewPlanId(next.getId());
            vo.setNewVersion(next.getVersion());
            vo.setBasePlanId(next.getParentId() == null ? vo.getBasePlanId() : next.getParentId());
            vo.setNote("已生成 v" + (next.getVersion() == null ? "?" : next.getVersion())
                    + " 计划（parent=v" + (vo.getBaseVersion() == null ? "?" : vo.getBaseVersion()) + "），请刷新查看");
        } else {
            vo.setNote("已生成下一版计划，请刷新查看");
        }
        vo.setCanAutoApply(false);
        return vo;
    }

    private String riskLevelOf(int adherence, Double rpeDev, int painDays, double low) {
        if (painDays > 0) {
            return "DANGER";
        }
        if (adherence < low) {
            return "WARN";
        }
        return "INFO";
    }

    private static double num(Map<String, Object> cfg, String key, double def) {
        Object v = cfg.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }

    private static String fmt(int v) {
        return String.valueOf(v);
    }

    /** 预留：用户档案调整时同步目标热量口径（P4 不改写，仅记录意图） */
    @SuppressWarnings("unused")
    private void syncTargetCalories(UserProfile profile, LocalDate date) {
        log.debug("目标热量口径由 BodyMetrics 统一提供，profile={} date={}", profile.getUserId(), date);
    }
}