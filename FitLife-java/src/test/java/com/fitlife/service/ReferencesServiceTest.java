package com.fitlife.service;

import com.fitlife.dto.SourceRef;
import com.fitlife.dto.ValidationReport;
import com.fitlife.entity.Exercise;
import com.fitlife.entity.FoodItem;
import com.fitlife.entity.KnowledgeItem;
import com.fitlife.repository.KnowledgeItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「AI 参考」不是一句口号：每份计划都要能吐出可点击的来源清单，
 * 且基线指南必须在，重复来源必须去重。
 */
class ReferencesServiceTest {

    private KnowledgeItemRepository repo;
    private ReferencesService service;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(KnowledgeItemRepository.class);
        Mockito.when(repo.findByCode(Mockito.anyString())).thenReturn(Optional.empty());
        service = new ReferencesService(repo);
    }

    @Test
    void guidelinesAlwaysPresent() {
        List<SourceRef> refs = service.guidelineRefs();
        assertTrue(refs.size() >= 5, "至少五条证据基座");
        assertTrue(refs.stream().anyMatch(r -> r.getRefCode().contains("ACSM")), "缺 ACSM");
        assertTrue(refs.stream().anyMatch(r -> r.getRefCode().contains("COMPENDIUM")), "缺 MET 汇编");
        assertTrue(refs.stream().anyMatch(r -> r.getRefCode().contains("WHO")), "缺 WHO 活动量指南");
        assertTrue(refs.stream().anyMatch(r -> r.getRefCode().contains("CFCT")), "缺中国食物成分表");
        refs.forEach(r -> {
            assertNotNull(r.getUrl(), "每条引用都要有链接");
            assertTrue(r.getUrl().startsWith("http"), "链接必须是 http(s)");
            assertNotNull(r.getEvidenceLevel(), "每条引用都要标证据等级");
            assertNotNull(r.getUsedFor(), "每条引用要说清被用在哪里");
        });
    }

    @Test
    void exerciseAndFoodSourcesAreCarriedThrough() {
        Exercise ex = Exercise.builder()
                .id(1L).name("深蹲").metValue(5.0)
                .sourceType("GUIDELINE").sourceTitle("ACSM 第11版").sourcePage("Ch.7")
                .sourceUrl("https://www.acsm.org/guidelines").evidenceLevel("A")
                .build();
        FoodItem food = FoodItem.builder()
                .id(2L).name("米饭(熟)").kcalPer100g(116.0).proteinG(2.6)
                .sourceCode("CFCT-6").sourceTitle("中国食物成分表 第6版")
                .sourceUrl("https://www.chinanutri.cn/").evidenceLevel("A")
                .build();

        List<SourceRef> refs = service.build(null, List.of(ex), List.of(food), null);

        SourceRef exRef = refs.stream().filter(r -> "EXERCISE".equals(r.getKind())).findFirst().orElseThrow();
        assertEquals("深蹲", exRef.getTitle());
        assertEquals("https://www.acsm.org/guidelines", exRef.getUrl());
        assertTrue(exRef.getUsedFor().contains("5.0"), "动作引用要带上 MET");

        // 食物来源与「中国食物成分表」基线是同一 URL，应合并为一条，但用途要都写进去
        SourceRef foodRef = refs.stream()
                .filter(r -> r.getUrl() != null && r.getUrl().contains("chinanutri"))
                .findFirst().orElseThrow();
        assertTrue(foodRef.getUsedFor().contains("116"), "食物引用要带上每 100g 热量");
        assertTrue(foodRef.getUsedFor().contains("kcal"), "热量必须带单位");
    }

    @Test
    void sourcesWithoutUrlAreSkipped() {
        Exercise noSource = Exercise.builder().id(3L).name("自制动作").metValue(6.0).build();
        List<SourceRef> refs = service.build(null, List.of(noSource), List.of(), null);
        assertTrue(refs.stream().noneMatch(r -> "EXERCISE".equals(r.getKind())),
                "没有来源的动作不应出现在引用清单里（应在入库前就被拦住）");
    }

    @Test
    void duplicatesAreMerged() {
        Exercise a = Exercise.builder().id(1L).name("深蹲").metValue(5.0)
                .sourceTitle("ACSM 第11版").sourceUrl("https://www.acsm.org/x").evidenceLevel("A").build();
        Exercise b = Exercise.builder().id(4L).name("硬拉").metValue(6.0)
                .sourceTitle("ACSM 第11版").sourceUrl("https://www.acsm.org/x").evidenceLevel("A").build();
        List<SourceRef> refs = service.build(null, List.of(a, b), List.of(), null);
        long acsmHits = refs.stream().filter(r -> "https://www.acsm.org/x".equals(r.getUrl())).count();
        assertEquals(1, acsmHits, "同一链接只保留一条");
    }

    @Test
    void knowledgeSnapshotResolvesBackToSource() {
        KnowledgeItem item = KnowledgeItem.builder()
                .code("HR-001").type("HEART_RATE").title("靶心率区间")
                .publisher("ACSM").sourceTitle("ACSM 运动处方指南 第11版")
                .sourceUrl("https://www.acsm.org/hr").evidenceLevel("A").build();
        Mockito.when(repo.findByCode("HR-001")).thenReturn(Optional.of(item));

        String snapshot = "[{\"code\":\"HR-001\",\"type\":\"HEART_RATE\",\"title\":\"靶心率区间\",\"version\":1}]";
        List<SourceRef> refs = service.build(snapshot, List.of(), List.of(), null);

        SourceRef kRef = refs.stream().filter(r -> "KNOWLEDGE".equals(r.getKind())).findFirst().orElseThrow();
        assertEquals("https://www.acsm.org/hr", kRef.getUrl());
        assertEquals("ACSM", kRef.getPublisher());
    }

    @Test
    void scienceNoteStatesEvidenceAndLimits() {
        ValidationReport report = new ValidationReport();
        report.setPassed(true);
        report.setScore(100);
        String note = PlanSupport.scienceNote(report, service.guidelineRefs(), true);
        assertTrue(note.contains("Karvonen"), "要点明靶心率的算法出处");
        assertTrue(note.contains("FITT"), "要点明知库约束");
        assertTrue(note.contains("kcal"), "饮食要说明热量单位");
        assertTrue(note.contains("不能替代"), "必须声明不替代医生/营养师");
    }
}