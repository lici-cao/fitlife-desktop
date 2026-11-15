package com.fitlife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitlife.dto.ApiResponse;
import com.fitlife.dto.GeneratePlanRequest;
import com.fitlife.dto.PlanVO;
import com.fitlife.dto.RevisePlanRequest;
import com.fitlife.dto.RevisePlanResponse;
import com.fitlife.dto.ValidationReport;
import com.fitlife.entity.Exercise;
import com.fitlife.entity.Plan;
import com.fitlife.entity.PlanDetail;
import com.fitlife.entity.UserProfile;
import com.fitlife.repository.ExerciseRepository;
import com.fitlife.repository.PlanDetailRepository;
import com.fitlife.repository.PlanRepository;
import com.fitlife.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P3 对话式局部修订测试（离线规则路径，全部依赖 mock）。
 */
@ExtendWith(MockitoExtension.class)
class PlanRevisionServiceTest {

    @Mock private PlanRepository planRepository;
    @Mock private PlanDetailRepository planDetailRepository;
    @Mock private ExerciseRepository exerciseRepository;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private PlanValidator planValidator;
    @Mock private KnowledgeRetriever knowledgeRetriever;
    @Mock private IntentRouter intentRouter;
    @Mock private ChatSessionService chatSessionService;
    @Mock private PlanService planService;

    private PlanRevisionService service;
    private ObjectMapper objectMapper = new ObjectMapper();

    private static final Long UID = 1L;

    private Plan active;
    private Exercise squat;
    private Exercise gluteBridge;
    private Exercise plank;

    @BeforeEach
    void setUp() {
        service = new PlanRevisionService(planRepository, planDetailRepository, exerciseRepository,
                userProfileRepository, planValidator, knowledgeRetriever, intentRouter,
                chatSessionService, planService, objectMapper);

        active = Plan.builder().id(10L).userId(UID).title("测试周计划").goal("fat_loss")
                .weeklyDays(3).sessionMinutes(45).summary("s").weeklyCardioMin(150)
                .weeklyCalories(1200.0).aiGenerated(false).status("ACTIVE").version(1)
                .sourceLink("TEMPLATE").build();

        squat = Exercise.builder().id(1L).name("深蹲").category("力量").muscleGroup("腿")
                .equipment("徒手").difficulty(1).metValue(5.0).build();
        gluteBridge = Exercise.builder().id(2L).name("臀桥").category("力量").muscleGroup("臀")
                .equipment("徒手").difficulty(1).metValue(4.0).build();
        plank = Exercise.builder().id(3L).name("平板支撑").category("核心").muscleGroup("核心")
                .equipment("徒手").difficulty(1).metValue(3.5).build();

        // save：回填 id（JPA 语义）
        lenient().when(planRepository.save(any(Plan.class))).thenAnswer(inv -> {
            Plan p = inv.getArgument(0);
            if (p.getId() == null) p.setId(20L);
            return p;
        });
    }

    private List<PlanDetail> details() {
        return List.of(
                PlanDetail.builder().planId(10L).dayOfWeek(3).dayFocus("腿部").exerciseId(1L)
                        .exerciseName("深蹲").muscleGroup("腿").sets(3).reps("10")
                        .restSec(90).rpe(7).seq(1).estCalories(100.0).build(),
                PlanDetail.builder().planId(10L).dayOfWeek(3).dayFocus("腿部").exerciseId(3L)
                        .exerciseName("平板支撑").muscleGroup("核心").sets(3).reps("40秒")
                        .restSec(45).rpe(6).seq(2).estCalories(40.0).build());
    }

    private UserProfile profile() {
        return UserProfile.builder().userId(UID).name("t").gender("male").age(30).height(175.0)
                .weight(70.0).goal("fat_loss").activityLevel("moderate").experience("初级")
                .weeklyDays(3).sessionMinutes(45).equipments("徒手").injuries("").build();
    }

    private void stubCommon() {
        lenient().when(planRepository.findFirstByUserIdAndStatusOrderByIdDesc(UID, "ACTIVE"))
                .thenReturn(Optional.of(active));
        lenient().when(planRepository.findMaxVersion(UID)).thenReturn(1);
        lenient().when(planDetailRepository.findByPlanIdOrderByDayOfWeekAscSeqAsc(10L))
                .thenReturn(details());
        lenient().when(exerciseRepository.findAllById(any())).thenReturn(List.of(squat, gluteBridge, plank));
        lenient().when(userProfileRepository.findByUserId(UID)).thenReturn(Optional.of(profile()));
        lenient().when(exerciseRepository.findCandidates(anyInt(), anyList()))
                .thenReturn(List.of(squat, gluteBridge, plank));
        lenient().when(planValidator.validate(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    ValidationReport r = new ValidationReport();
                    r.setPassed(true);
                    return r;
                });
    }

    private RevisePlanRequest req(String message) {
        RevisePlanRequest r = new RevisePlanRequest();
        r.setMessage(message);
        return r;
    }

    @Test
    @DisplayName("替换：把周三的深蹲换成臀桥 → v2 + diff + parent 归档")
    void replaceSquatWithGluteBridge() {
        stubCommon();
        when(intentRouter.route(eq(UID), anyString(), any(), eq(true))).thenReturn(
                new IntentRouter.RouteResult(IntentRouter.REVISE, IntentRouter.OP_REPLACE,
                        3, "深蹲", null, "臀桥", false, null));

        RevisePlanResponse resp = service.handle(UID, req("把周三的深蹲换成臀桥"));

        assertEquals("REVISE", resp.getAction());
        assertNotNull(resp.getDiff());
        assertEquals(1, resp.getDiff().size());
        assertEquals("MODIFIED", resp.getDiff().get(0).getType());
        assertTrue(resp.getDiff().get(0).getDetail().contains("深蹲"));
        assertTrue(resp.getDiff().get(0).getDetail().contains("臀桥"));
        assertNotNull(resp.getPlan());
        assertEquals(2, resp.getPlan().getVersion());
        assertEquals(10L, resp.getPlan().getParentId());
        assertEquals("REVISED", resp.getPlan().getSourceLink());
        // 旧版本归档
        assertEquals("ARCHIVED", active.getStatus());
        // 新计划里周三的动作换成臀桥
        assertEquals("臀桥", resp.getPlan().getDays().get(2).getItems().get(0).getExerciseName());
        // 平板支撑未动
        assertEquals("平板支撑", resp.getPlan().getDays().get(2).getItems().get(1).getExerciseName());
        // 落库新明细
        verify(planDetailRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("移除：去掉深蹲 → REMOVED diff")
    void removeSquat() {
        stubCommon();
        when(intentRouter.route(eq(UID), anyString(), any(), eq(true))).thenReturn(
                new IntentRouter.RouteResult(IntentRouter.REVISE, IntentRouter.OP_REMOVE,
                        3, "深蹲", null, null, false, null));

        RevisePlanResponse resp = service.handle(UID, req("去掉深蹲"));

        assertEquals("REVISE", resp.getAction());
        assertEquals(1, resp.getDiff().size());
        assertEquals("REMOVED", resp.getDiff().get(0).getType());
        // 该天只剩平板支撑
        assertEquals(1, resp.getPlan().getDays().get(2).getItems().size());
        assertEquals("平板支撑", resp.getPlan().getDays().get(2).getItems().get(0).getExerciseName());
    }

    @Test
    @DisplayName("同名前缀动作不被误删（反向 contains 回归）")
    void removeExactNameDoesNotHitSibling() {
        // 周三含「深蹲」与「徒手深蹲」两个动作，只应删除精确命中的「徒手深蹲」
        lenient().when(planRepository.findFirstByUserIdAndStatusOrderByIdDesc(UID, "ACTIVE"))
                .thenReturn(Optional.of(active));
        lenient().when(planRepository.findMaxVersion(UID)).thenReturn(1);
        lenient().when(planDetailRepository.findByPlanIdOrderByDayOfWeekAscSeqAsc(10L)).thenReturn(List.of(
                PlanDetail.builder().planId(10L).dayOfWeek(3).dayFocus("腿部").exerciseId(1L)
                        .exerciseName("深蹲").muscleGroup("腿").sets(3).reps("10")
                        .restSec(90).rpe(7).seq(1).estCalories(100.0).build(),
                PlanDetail.builder().planId(10L).dayOfWeek(3).dayFocus("腿部").exerciseId(4L)
                        .exerciseName("徒手深蹲").muscleGroup("腿").sets(3).reps("15")
                        .restSec(90).rpe(6).seq(2).estCalories(110.0).build()));
        lenient().when(userProfileRepository.findByUserId(UID)).thenReturn(Optional.of(profile()));
        lenient().when(exerciseRepository.findAllById(any())).thenReturn(List.of(squat, gluteBridge, plank));

        when(intentRouter.route(eq(UID), anyString(), any(), eq(true))).thenReturn(
                new IntentRouter.RouteResult(IntentRouter.REVISE, IntentRouter.OP_REMOVE,
                        3, "徒手深蹲", null, null, false, null));

        RevisePlanResponse resp = service.handle(UID, req("去掉徒手深蹲"));

        assertEquals("REVISE", resp.getAction());
        assertEquals(1, resp.getDiff().size());
        assertEquals("徒手深蹲", resp.getDiff().get(0).getExerciseName());
        // 深蹲保留
        assertEquals(1, resp.getPlan().getDays().get(2).getItems().size());
        assertEquals("深蹲", resp.getPlan().getDays().get(2).getItems().get(0).getExerciseName());
    }

    @Test
    @DisplayName("降强度：目标项 RPE 7 → 6")
    void lowerRpe() {
        stubCommon();
        when(intentRouter.route(eq(UID), anyString(), any(), eq(true))).thenReturn(
                new IntentRouter.RouteResult(IntentRouter.REVISE, IntentRouter.OP_ADJUST_RPE,
                        3, "深蹲", null, null, false, "降低强度"));

        RevisePlanResponse resp = service.handle(UID, req("强度降一点"));

        assertEquals("REVISE", resp.getAction());
        assertTrue(resp.getDiff().stream().anyMatch(d -> d.getDetail().contains("RPE 7→6")));
        assertEquals(6, resp.getPlan().getDays().get(2).getItems().get(0).getRpe());
    }

    @Test
    @DisplayName("无生效计划 + REVISE 意图 → 提示先生成，不落新版本")
    void reviseWithoutActivePlan() {
        when(planRepository.findFirstByUserIdAndStatusOrderByIdDesc(UID, "ACTIVE"))
                .thenReturn(Optional.empty());
        when(intentRouter.route(eq(UID), anyString(), any(), eq(false))).thenReturn(
                new IntentRouter.RouteResult(IntentRouter.REVISE, IntentRouter.OP_REPLACE,
                        null, null, null, null, false, null));

        RevisePlanResponse resp = service.handle(UID, req("把深蹲换成臀桥"));

        assertEquals("QUERY", resp.getAction());
        assertTrue(resp.getReply().contains("生成"));
        verify(planDetailRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("目标动作定位不到 → REVISE_SKIPPED，不改库")
    void reviseTargetNotFound() {
        stubCommon();
        when(intentRouter.route(eq(UID), anyString(), any(), eq(true))).thenReturn(
                new IntentRouter.RouteResult(IntentRouter.REVISE, IntentRouter.OP_REMOVE,
                        3, "不存在的动作", null, null, false, null));

        RevisePlanResponse resp = service.handle(UID, req("去掉不存在的动作"));

        assertEquals("REVISE_SKIPPED", resp.getAction());
        assertEquals("ACTIVE", active.getStatus());
        verify(planDetailRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("生成意图 → 委托主链路 force=true")
    void generateDelegatesToOrchestrator() {
        when(planRepository.findFirstByUserIdAndStatusOrderByIdDesc(UID, "ACTIVE"))
                .thenReturn(Optional.of(active));
        when(intentRouter.route(eq(UID), anyString(), any(), eq(true))).thenReturn(
                IntentRouter.RouteResult.of(IntentRouter.GENERATE));
        PlanVO vo = PlanVO.builder().id(30L).version(2).sourceLink("LLM").build();
        ValidationReport rep = new ValidationReport();
        rep.setPassed(true);
        vo.setValidationReport(rep);
        when(planService.generatePlan(eq(UID), any(GeneratePlanRequest.class)))
                .thenReturn(ApiResponse.success(vo));

        RevisePlanResponse resp = service.handle(UID, req("重新生成一份计划"));

        assertEquals("GENERATE", resp.getAction());
        assertNotNull(resp.getPlan());
        // 确认委托时 force=true
        verify(planService).generatePlan(eq(UID), argThat(g -> Boolean.TRUE.equals(g.getForce())));
    }
}
