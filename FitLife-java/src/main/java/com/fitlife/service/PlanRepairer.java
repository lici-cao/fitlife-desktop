package com.fitlife.service;

import com.fitlife.dto.PlanDayVO;
import com.fitlife.dto.PlanItemVO;
import com.fitlife.dto.PlanVO;
import com.fitlife.dto.ValidationReport;
import com.fitlife.entity.Exercise;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划最小修复器（P2，对应图 2 第 ⑪ 步）。
 *
 * <p>只改动违例点，其余动作与安排保持不变（可验证的 Diff），
 * 每条修复写入 change_note。编排器最多调用 2 轮，仍不过则降级模板。
 */
@Slf4j
@Service
public class PlanRepairer {

    /** @return 修复动作清单（写入 plans.change_note） */
    public List<String> repair(PlanVO vo, Map<Long, Exercise> candidateById,
                               ValidationReport report, PlanValidator.VContext ctx) {
        List<String> actions = new ArrayList<>();
        Set<String> levels = new LinkedHashSet<>();
        for (ValidationReport.Check c : report.getChecks()) {
            if (!"ERROR".equals(c.getLevel()) && !"WARN".equals(c.getLevel())) {
                continue;
            }
            levels.add(c.getCode() + ":" + c.getLevel());
        }

        // V5 禁忌：同肌群替代，无替代则删除
        if (levels.contains("V5:ERROR")) {
            repairContraindication(vo, candidateById, ctx, actions);
        }

        // V1 强度越界：RPE 压回安全区
        if (levels.contains("V1:ERROR") || levels.contains("V1:WARN")) {
            repairRpe(vo, ctx, actions);
        }

        // V4 恢复冲突：后一天降强度
        if (levels.contains("V4:ERROR") || levels.contains("V4:WARN")) {
            repairRecovery(vo, actions);
        }

        // V2 减脂有氧不足：末训练日追加一个有氧
        if (levels.contains("V2:WARN")) {
            repairCardio(vo, candidateById, ctx, actions);
        }

        // V6 单次动作过多：删尾部
        if (levels.contains("V6:WARN")) {
            repairCapacity(vo, actions);
        }

        if (!actions.isEmpty()) {
            PlanSupport.reSummarizeWeek(vo);
        }
        return actions;
    }

    // ------------------------------------------------------------------

    private void repairContraindication(PlanVO vo, Map<Long, Exercise> candidateById,
                                        PlanValidator.VContext ctx, List<String> actions) {
        Set<String> injuries = new LinkedHashSet<>(ctx.injuries());
        for (PlanDayVO d : vo.getDays()) {
            if (d.getItems() == null) {
                continue;
            }
            List<PlanItemVO> keep = new ArrayList<>();
            for (PlanItemVO it : d.getItems()) {
                Exercise ex = it.getExerciseId() == null ? null : candidateById.get(it.getExerciseId());
                if (ex == null || !PlanSupport.hitContraindication(ex, injuries)) {
                    keep.add(it);
                    continue;
                }
                Exercise sub = candidateById.values().stream()
                        .filter(c -> !PlanSupport.hitContraindication(c, injuries))
                        .filter(c -> ex.getMuscleGroup() != null && ex.getMuscleGroup().equals(c.getMuscleGroup()))
                        .filter(c -> c.getDifficulty() == null || c.getDifficulty() <= ctx.level())
                        .min(Comparator.comparingInt(c -> c.getDifficulty() == null ? 9 : c.getDifficulty()))
                        .orElse(null);
                if (sub != null) {
                    it.setExerciseId(sub.getId());
                    it.setExerciseName(sub.getName());
                    it.setCategory(sub.getCategory());
                    it.setDescription(sub.getDescription());
                    keep.add(it);
                    actions.add(d.getDayLabel() + "「" + ex.getName() + "」→「" + sub.getName()
                            + "」（伤病" + String.join("/", injuries) + "禁忌替换）");
                } else {
                    actions.add(d.getDayLabel() + "删除「" + ex.getName() + "」（无安全替代）");
                }
            }
            d.setItems(keep);
            if (keep.isEmpty() && !Boolean.TRUE.equals(d.getRestDay())) {
                d.setRestDay(true);
                d.setFocus("休息（修复后）");
            }
        }
    }

    private void repairRpe(PlanVO vo, PlanValidator.VContext ctx, List<String> actions) {
        int cap = ctx.level() == 1 ? 6 : 8;
        for (PlanDayVO d : vo.getDays()) {
            if (d.getItems() == null) {
                continue;
            }
            for (PlanItemVO it : d.getItems()) {
                int rpe = it.getRpe() == null ? 6 : it.getRpe();
                if (rpe > cap) {
                    it.setRpe(cap);
                    actions.add(d.getDayLabel() + "「" + it.getExerciseName() + "」RPE " + rpe
                            + "→" + cap + "（强度越界回收）");
                }
            }
        }
    }

    private void repairRecovery(PlanVO vo, List<String> actions) {
        // 同肌群出现在相邻两天：把后一天的该肌群动作 RPE 降 1（不低于 5），缓解连续高强度
        Map<String, Integer> lastDay = new java.util.HashMap<>();
        for (PlanDayVO d : vo.getDays()) {
            if (Boolean.TRUE.equals(d.getRestDay()) || d.getItems() == null) {
                continue;
            }
            int dow = d.getDayOfWeek() == null ? 0 : d.getDayOfWeek();
            Set<String> muscles = new LinkedHashSet<>();
            d.getItems().forEach(it -> {
                if (it.getMuscleGroup() != null && !"全身".equals(it.getMuscleGroup())) {
                    muscles.add(it.getMuscleGroup());
                }
            });
            for (String m : muscles) {
                Integer prev = lastDay.get(m);
                if (prev != null && dow - prev == 1) {
                    for (PlanItemVO it : d.getItems()) {
                        if (m.equals(it.getMuscleGroup()) && it.getRpe() != null && it.getRpe() > 5) {
                            it.setRpe(Math.max(5, it.getRpe() - 1));
                            actions.add(d.getDayLabel() + "「" + it.getExerciseName()
                                    + "」与周" + prev + "同肌群连日训练，RPE 降 1 缓解恢复压力");
                        }
                    }
                }
                lastDay.put(m, dow);
            }
        }
    }

    private void repairCardio(PlanVO vo, Map<Long, Exercise> candidateById,
                              PlanValidator.VContext ctx, List<String> actions) {
        if (!PlanSupport.isCutGoal(ctx.goal())) {
            return;
        }
        Exercise c = candidateById.values().stream()
                .filter(e -> "有氧".equals(e.getCategory()))
                .min(Comparator.comparingInt(e -> e.getDifficulty() == null ? 9 : e.getDifficulty()))
                .orElse(null);
        if (c == null) {
            return;
        }
        PlanDayVO target = null;
        for (PlanDayVO d : vo.getDays()) {
            if (!Boolean.TRUE.equals(d.getRestDay()) && d.getItems() != null && !d.getItems().isEmpty()) {
                target = d;
            }
        }
        if (target == null || target.getItems().size() >= 7) {
            return;
        }
        PlanItemVO item = PlanItemVO.builder()
                .exerciseId(c.getId()).exerciseName(c.getName())
                .muscleGroup(c.getMuscleGroup()).category(c.getCategory())
                .sets(1).reps("20分钟").restSec(0).rpe(5)
                .seq(target.getItems().size() + 1)
                .description(c.getDescription())
                .estCalories(PlanSupport.estimateCalories(c, 1, "20分钟", ctx.weightKg()))
                .build();
        target.getItems().add(item);
        PlanSupport.reSummarize(target, ctx.weightKg(), candidateById);
        actions.add(target.getDayLabel() + "追加有氧「" + c.getName() + "」20 分钟（减脂周有氧不足）");
    }

    private void repairCapacity(PlanVO vo, List<String> actions) {
        for (PlanDayVO d : vo.getDays()) {
            if (Boolean.TRUE.equals(d.getRestDay()) || d.getItems() == null) {
                continue;
            }
            while (d.getItems().size() > 6) {
                PlanItemVO removed = d.getItems().remove(d.getItems().size() - 1);
                actions.add(d.getDayLabel() + "删除尾部动作「" + removed.getExerciseName()
                        + "」（单次容量超预算）");
            }
            int seq = 1;
            for (PlanItemVO it : d.getItems()) {
                it.setSeq(seq++);
            }
        }
    }
}
