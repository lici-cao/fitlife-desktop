package com.fitlife.service;

import com.fitlife.dto.ProgressAdviceVO;
import com.fitlife.dto.StatsSummaryVO;
import com.fitlife.entity.Plan;
import com.fitlife.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调整决策单测（图 4 ⑤）：疼痛 > 依从率过低 > RPE 偏低 > 依从率达标；其余 HOLD。
 * 关键约束：只有 PROGRESS 允许"一键应用"，且必须经过计划主链路的 ≤10% 复核。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProgressServiceTest {

    @Mock private StatsService statsService;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private PlanService planService;

    private ProgressService service;

    @BeforeEach
    void setUp() {
        service = new ProgressService(statsService, userProfileRepository, planService);
        when(statsService.adviceConfig()).thenReturn(new HashMap<>());
        when(planService.currentPlan(anyLong())).thenReturn(Optional.of(
                Plan.builder().id(7L).userId(1L).status("ACTIVE").version(2).build()));
    }

    @Test
    @DisplayName("疼痛上报 → STOP，且禁止一键进阶")
    void painStopsAdvance() {
        stub(80, 0.0, 2, 10, 8);
        ProgressAdviceVO vo = service.advice(1L, 14);

        assertThat(vo.getDecision()).isEqualTo("STOP");
        assertThat(vo.getCanAutoApply()).isFalse();
        assertThat(vo.getRiskLevel()).isEqualTo("DANGER");
        assertThat(vo.getReasons()).anyMatch(r -> r.contains("疼痛"));
        verify(planService, never()).generateNextVersion(anyLong(), anyString());
    }

    @Test
    @DisplayName("依从率低于下限 → DELOAD（降载），不自动应用")
    void lowAdherenceDeloads() {
        stub(55, 0.0, 0, 9, 5);

        ProgressAdviceVO vo = service.advice(1L, 14);

        assertThat(vo.getDecision()).isEqualTo("DELOAD");
        assertThat(vo.getCanAutoApply()).isFalse();
        assertThat(vo.getSuggestions()).isNotEmpty();
    }

    @Test
    @DisplayName("RPE 明显低于计划 → SWAP（换动作/加量提示）")
    void lowRpeSuggestsSwap() {
        stub(95, -2.0, 0, 12, 12);

        ProgressAdviceVO vo = service.advice(1L, 14);

        assertThat(vo.getDecision()).isEqualTo("SWAP");
        assertThat(vo.getCanAutoApply()).isFalse();
    }

    @Test
    @DisplayName("依从率达标且强度合适 → PROGRESS，可一键应用且幅度 10%")
    void highAdherenceProgress() {
        stub(96, 0.2, 0, 12, 12);

        ProgressAdviceVO vo = service.advice(1L, 14);

        assertThat(vo.getDecision()).isEqualTo("PROGRESS");
        assertThat(vo.getCanAutoApply()).isTrue();
        assertThat(vo.getVolumeChangePct()).isEqualTo(10);
        assertThat(vo.getBasePlanId()).isEqualTo(7L);
        assertThat(vo.getBaseVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("样本不足 → HOLD，不给出加量建议")
    void insufficientSamplesHold() {
        stub(100, null, 0, 1, 1);

        ProgressAdviceVO vo = service.advice(1L, 14);

        assertThat(vo.getDecision()).isEqualTo("HOLD");
        assertThat(vo.getCanAutoApply()).isFalse();
        assertThat(vo.getTitle()).contains("样本不足");
    }

    @Test
    @DisplayName("一键进阶委托主链路（≤10% 复核由编排器/校验器保证）")
    void advanceDelegatesToPlanService() {
        stub(96, 0.2, 0, 12, 12);
        when(planService.generateNextVersion(anyLong(), anyString()))
                .thenReturn(com.fitlife.dto.ApiResponse.success("ok"));

        ProgressAdviceVO result = service.advance(1L, 14);

        assertThat(result.getNote()).contains("已生成下一版计划");
        assertThat(result.getCanAutoApply()).isFalse();
        verify(planService).generateNextVersion(eq(1L), argThat(s -> s.contains("依从率 96%")));
    }

    // ---------- helpers ----------

    private void stub(int adherence, Double rpeDev, int painDays, int planned, int done) {
        StatsSummaryVO stats = new StatsSummaryVO();
        stats.setAdherenceRate(adherence);
        stats.setRpeDeviation(rpeDev);
        stats.setPainDays(painDays);
        stats.setPlannedItems(planned);
        stats.setDoneItems(done);
        when(statsService.summary(anyLong(), any(), any())).thenReturn(stats);
    }
}