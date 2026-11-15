package com.fitlife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.entity.KnowledgeItem;
import com.fitlife.repository.KnowledgeItemRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 健身知识库检索器（P2）。
 *
 * <p>开题技术路线里的「知识库检索约束」环节。当前实现为关键词打分检索
 * （报告排期约束允许「向量检索降级为关键词+规则表先行」），命中条目的正文
 * 注入 Prompt 的约束段，条目编码快照进 plans.knowledge_snapshot 供溯源。
 * 换成 embedding 检索只需替换本类的 score()，上下游契约不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetriever {

    private final KnowledgeItemRepository knowledgeItemRepository;
    private final ObjectMapper objectMapper;

    /** 检索上限：注入 Prompt 的条目数 */
    private static final int TOP_K = 8;

    @Data
    public static class RetrievalResult {
        /** 注入 Prompt「运动处方约束」段的文本（可为空串） */
        private String constraintText = "";
        /** 命中条目快照 JSON（code/type/title/version），落 knowledge_snapshot */
        private String snapshotJson = "[]";
        /** 命中条目的禁忌关键词集合（校验器 V5 结合动作库使用） */
        private List<String> injuryTokens = new ArrayList<>();
        private int hitCount;
    }

    /**
     * @param goal     训练目标（fat_loss / muscle_gain / …，可空）
     * @param injuries 伤病逗号串（可空）
     * @param muscles  本次关注的肌群词（可空）
     */
    public RetrievalResult retrieve(String goal, String injuries, List<String> muscles) {
        RetrievalResult result = new RetrievalResult();
        List<KnowledgeItem> items;
        try {
            items = knowledgeItemRepository.findByEnabled(1);
        } catch (Exception e) {
            log.warn("知识库读取失败，本次生成不注入约束: {}", e.getMessage());
            return result;
        }
        if (items == null || items.isEmpty()) {
            return result;
        }

        String g = goal == null ? "" : goal.toLowerCase(Locale.ROOT);
        List<String> injuryList = splitCsv(injuries);
        result.setInjuryTokens(injuryList);

        List<KnowledgeItem> ranked = items.stream()
                .map(item -> Map.entry(item, score(item, g, injuryList, muscles)))
                .filter(en -> en.getValue() > 0)
                .sorted(Comparator.comparingInt((Map.Entry<KnowledgeItem, Integer> en) -> en.getValue())
                        .reversed()
                        .thenComparing(en -> en.getKey().getCode()))
                .limit(TOP_K)
                .map(Map.Entry::getKey)
                .toList();

        if (ranked.isEmpty()) {
            return result;
        }

        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (KnowledgeItem item : ranked) {
            text.append("· [").append(item.getCode()).append("] ")
                    .append(item.getTitle()).append("：")
                    .append(clip(item.getContent(), 160)).append('\n');
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", item.getCode());
            row.put("type", item.getType());
            row.put("title", item.getTitle());
            row.put("version", item.getVersion());
            snapshot.add(row);
        }
        result.setConstraintText(text.toString());
        result.setHitCount(ranked.size());
        try {
            result.setSnapshotJson(objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            result.setSnapshotJson("[]");
        }
        return result;
    }

    /** 打分：目标匹配 +2；关键词命中每个 +1（封顶 3）；伤病时禁忌/替代类 +3；处方通用类保底 +1 */
    private int score(KnowledgeItem item, String goal, List<String> injuries, List<String> muscles) {
        int s = 0;
        String scope = item.getGoalScope() == null ? "" : item.getGoalScope().toLowerCase(Locale.ROOT);
        if (scope.isBlank()) {
            s += 1;
        } else if (!goal.isBlank() && scope.contains(goal)) {
            s += 2;
        }
        List<String> kws = splitCsv(item.getKeywords());
        int kwHits = 0;
        for (String kw : kws) {
            String k = kw.toLowerCase(Locale.ROOT);
            boolean hit = (!goal.isBlank() && (k.contains(goal) || goal.contains(k)))
                    || injuries.stream().anyMatch(inj -> k.contains(inj) || inj.contains(k))
                    || (muscles != null && muscles.stream().anyMatch(m -> k.contains(m) || m.contains(k)));
            if (hit) {
                kwHits++;
            }
        }
        s += Math.min(kwHits, 3);
        if (!injuries.isEmpty()
                && ("CONTRAINDICATION".equals(item.getType()) || "SUBSTITUTE".equals(item.getType()))) {
            s += 3;
        }
        return s;
    }

    private static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String part : csv.split("[,，]")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
