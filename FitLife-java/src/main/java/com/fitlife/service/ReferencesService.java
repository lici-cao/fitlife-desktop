package com.fitlife.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.dto.SourceRef;
import com.fitlife.dto.ValidationReport;
import com.fitlife.entity.Exercise;
import com.fitlife.entity.FoodItem;
import com.fitlife.entity.KnowledgeItem;
import com.fitlife.repository.KnowledgeItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引用清单装配（P5）。
 *
 * <p>回答「AI 生成的计划凭什么可信」：把本版计划实际用到的证据全部列出来，
 * 每条都带 URL、发布机构与证据等级，用户点开就能核对。来源分四类：
 * <ul>
 *   <li>GUIDELINE —— 证据基座（ACSM / NSCA / WHO / 食物成分表），恒定给出；</li>
 *   <li>KNOWLEDGE —— 本次注入 Prompt 的知识条目（来自知识快照）；</li>
 *   <li>EXERCISE —— 训练动作的出处；</li>
 *   <li>FOOD —— 饮食明细用到的食物成分数据源。</li>
 * </ul>
 * 去重按「kind + title + url」，并限制总量，避免响应体膨胀。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReferencesService {

    private static final int MAX_REFS = 40;

    private final KnowledgeItemRepository knowledgeItemRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 证据基座：四个公开可核对的来源，任何一份计划都引用 */
    public List<SourceRef> guidelineRefs() {
        List<SourceRef> refs = new ArrayList<>();
        refs.add(SourceRef.builder()
                .kind("GUIDELINE").refCode("ACSM-11")
                .title("ACSM 运动测试与运动处方指南（第 11 版）")
                .publisher("American College of Sports Medicine")
                .sourceTitle("ACSM's Guidelines for Exercise Testing and Prescription, 11th ed.")
                .url("https://www.acsm.org/education-resources/books/guidelines-exercise-testing-prescription")
                .evidenceLevel("A")
                .usedFor("FITT-VP 编排原则、靶心率区间、强度分级")
                .build());
        refs.add(SourceRef.builder()
                .kind("GUIDELINE").refCode("COMPENDIUM-2024")
                .title("2024 成人身体活动能量消耗纲要")
                .publisher("Herrmann et al., 2024 Adult Compendium of Physical Activities")
                .sourceTitle("2024 Adult Compendium of Physical Activities (MET values)")
                .url("https://pacompendium.com/adult-compendium/")
                .evidenceLevel("A")
                .usedFor("动作 MET 值与热量估算（kcal = MET × 体重kg × 小时 × 1.05）")
                .build());
        refs.add(SourceRef.builder()
                .kind("GUIDELINE").refCode("WHO-2020")
                .title("WHO 身体活动与久坐行为指南（2020）")
                .publisher("World Health Organization")
                .sourceTitle("WHO Guidelines on Physical Activity and Sedentary Behaviour")
                .url("https://www.who.int/publications/i/item/9789240015128")
                .evidenceLevel("A")
                .usedFor("每周有氧时长下限（150 分钟中等强度）与频次建议")
                .build());
        refs.add(SourceRef.builder()
                .kind("GUIDELINE").refCode("CFCT-6")
                .title("中国食物成分表（第 6 版）")
                .publisher("中国疾病预防控制中心营养与健康所")
                .sourceTitle("中国食物成分表 标准版 第 6 版")
                .url("https://www.chinanutri.cn/")
                .evidenceLevel("A")
                .usedFor("饮食明细每 100g 热量与三大营养素")
                .build());
        refs.add(SourceRef.builder()
                .kind("GUIDELINE").refCode("NSCA-4")
                .title("NSCA 力量训练与体能训练精要（第 4 版）")
                .publisher("National Strength and Conditioning Association")
                .sourceTitle("Essentials of Strength Training and Conditioning, 4th ed.")
                .url("https://www.nsca.com/education/books/essentials-of-strength-training-and-conditioning/")
                .evidenceLevel("A")
                .usedFor("组数/次数/RPE 与同肌群恢复间隔（≥48 小时）")
                .build());
        return refs;
    }

    /**
     * 装配本版引用清单。
     *
     * @param knowledgeSnapshotJson KnowledgeRetriever 产出的快照（[{code,type,title,version}]）
     * @param usedExercises        本版计划实际用到的动作
     * @param usedFoods            本版饮食实际用到的食物
     * @param report               校验报告，用于把「校验依据」标注到 usedFor
     */
    public List<SourceRef> build(String knowledgeSnapshotJson,
                                 Collection<Exercise> usedExercises,
                                 Collection<FoodItem> usedFoods,
                                 ValidationReport report) {
        Map<String, SourceRef> map = new LinkedHashMap<>();
        for (SourceRef ref : guidelineRefs()) {
            mergeIn(map, ref);
        }
        for (SourceRef ref : fromKnowledge(knowledgeSnapshotJson)) {
            mergeIn(map, ref);
        }
        for (Exercise e : usedExercises == null ? List.<Exercise>of() : usedExercises) {
            if (e.getSourceUrl() == null || e.getSourceUrl().isBlank()) {
                continue;
            }
            SourceRef ref = SourceRef.builder()
                    .kind("EXERCISE").refCode("EX-" + e.getId())
                    .title(e.getName())
                    .publisher(publisherOf(e.getSourceType()))
                    .sourceTitle(e.getSourceTitle())
                    .url(e.getSourceUrl())
                    .evidenceLevel(e.getEvidenceLevel())
                    .usedFor("训练动作（MET " + (e.getMetValue() == null ? "—" : e.getMetValue())
                            + (e.getSourcePage() == null || e.getSourcePage().isBlank() ? "" : "，" + e.getSourcePage())
                            + "）")
                    .build();
            mergeIn(map, ref);
        }
        for (FoodItem f : usedFoods == null ? List.<FoodItem>of() : usedFoods) {
            if (f.getSourceUrl() == null || f.getSourceUrl().isBlank()) {
                continue;
            }
            SourceRef ref = SourceRef.builder()
                    .kind("FOOD").refCode("FD-" + f.getId())
                    .title(f.getName())
                    .publisher(f.getSourceCode())
                    .sourceTitle(f.getSourceTitle())
                    .url(f.getSourceUrl())
                    .evidenceLevel(f.getEvidenceLevel())
                    .usedFor(String.format("饮食明细：%s kcal/100g，蛋白 %s g/100g",
                            trimNum(f.getKcalPer100g()), trimNum(f.getProteinG())))
                    .build();
            mergeIn(map, ref);
        }
        List<SourceRef> all = new ArrayList<>(map.values());
        if (all.size() > MAX_REFS) {
            all = new ArrayList<>(all.subList(0, MAX_REFS));
        }
        if (report != null && report.getChecks() != null) {
            log.debug("引用装配完成：{} 条，校验项 {} 个", all.size(), report.getChecks().size());
        }
        return all;
    }

    /** 知识快照 → 知识条目引用（回表取 publisher/url，避免快照里存正文） */
    private List<SourceRef> fromKnowledge(String snapshotJson) {
        List<SourceRef> refs = new ArrayList<>();
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return refs;
        }
        try {
            JsonNode arr = objectMapper.readTree(snapshotJson);
            if (!arr.isArray()) {
                return refs;
            }
            for (JsonNode node : arr) {
                String code = node.path("code").asText(null);
                if (code == null) {
                    continue;
                }
                KnowledgeItem item = knowledgeItemRepository.findByCode(code).orElse(null);
                if (item == null) {
                    continue;
                }
                refs.add(SourceRef.builder()
                        .kind("KNOWLEDGE").refCode(item.getCode())
                        .title(item.getTitle())
                        .publisher(item.getPublisher())
                        .sourceTitle(item.getSourceTitle())
                        .url(item.getSourceUrl())
                        .evidenceLevel(item.getEvidenceLevel())
                        .usedFor("约束注入：" + typeLabel(item.getType()))
                        .build());
            }
        } catch (Exception e) {
            log.warn("知识快照解析失败，引用清单只保留指南基座：{}", e.getMessage());
        }
        return refs;
    }

    private static String typeLabel(String type) {
        if (type == null) {
            return "规则";
        }
        return switch (type) {
            case "FITT" -> "FITT-VP 频率/强度/时间";
            case "HR" -> "靶心率与强度分级";
            case "PROGRESSION" -> "渐进超负荷";
            case "RECOVERY" -> "恢复间隔";
            case "CONTRAINDICATION" -> "禁忌人群";
            case "SUBSTITUTE" -> "动作替代";
            case "TECHNIQUE" -> "动作要领";
            case "POPULATION" -> "特殊人群";
            case "NUTRITION" -> "营养分配";
            default -> type;
        };
    }

    private static String publisherOf(String sourceType) {
        if (sourceType == null) {
            return null;
        }
        return switch (sourceType) {
            case "GUIDELINE" -> "运动医学/公共卫生指南";
            case "TEXTBOOK" -> "专业教材";
            case "JOURNAL" -> "同行评议期刊";
            case "DATABASE" -> "公开数据库";
            case "COMMUNITY" -> "社区整理";
            default -> sourceType;
        };
    }


    /**
     * 合并同一来源：同一份文献被多个动作/食物引用时，来源清单里只留一条，
     * 但把各自的用途追加到 usedFor，避免用户以为只被用在一处。
     */
    private static void mergeIn(Map<String, SourceRef> map, SourceRef ref) {
        String k = key(ref);
        SourceRef exists = map.get(k);
        if (exists == null) {
            map.put(k, ref);
            return;
        }
        String a = exists.getUsedFor() == null ? "" : exists.getUsedFor();
        String b = ref.getUsedFor() == null ? "" : ref.getUsedFor();
        if (!b.isEmpty() && !a.contains(b)) {
            exists.setUsedFor(a.isEmpty() ? b : a + "；" + b);
        }
        if (exists.getEvidenceLevel() == null) {
            exists.setEvidenceLevel(ref.getEvidenceLevel());
        }
        if (exists.getPublisher() == null || exists.getPublisher().isBlank()) {
            exists.setPublisher(ref.getPublisher());
        }
    }

    private static String key(SourceRef r) {
        // 有 URL 时按 URL 去重：同一份指南被多个动作引用，来源清单里只应出现一条
        String url = r.getUrl() == null ? "" : r.getUrl().trim();
        if (!url.isEmpty()) {
            return "URL|" + url;
        }
        return r.getKind() + "|" + (r.getTitle() == null ? "" : r.getTitle());
    }

    private static String trimNum(Double v) {
        if (v == null) {
            return "—";
        }
        return Math.abs(v - Math.rint(v)) < 0.05 ? String.valueOf((long) Math.rint(v)) : String.format("%.1f", v);
    }
}