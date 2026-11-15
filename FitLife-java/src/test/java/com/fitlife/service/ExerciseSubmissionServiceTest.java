package com.fitlife.service;

import com.fitlife.common.BusinessException;
import com.fitlife.dto.ExerciseSubmissionDTO;
import com.fitlife.entity.Exercise;
import com.fitlife.entity.ExerciseSubmission;
import com.fitlife.repository.ExerciseRepository;
import com.fitlife.repository.ExerciseSubmissionRepository;
import com.fitlife.repository.KnowledgeRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 投稿审核的服务端门槛：机器能判的（格式、区间、重名、链接）必须先拦住，
 * 人只审「来源是否真能查到、描述有没有被改坏」。
 */
class ExerciseSubmissionServiceTest {

    private ExerciseSubmissionRepository subRepo;
    private ExerciseRepository exRepo;
    private ExerciseSubmissionService service;

    @BeforeEach
    void setUp() {
        subRepo = Mockito.mock(ExerciseSubmissionRepository.class);
        exRepo = Mockito.mock(ExerciseRepository.class);
        KnowledgeRuleRepository ruleRepo = Mockito.mock(KnowledgeRuleRepository.class);
        Mockito.when(ruleRepo.findByCode("CONTENT_REVIEW")).thenReturn(Optional.empty());
        Mockito.when(exRepo.existsByNameIgnoreCase(Mockito.anyString())).thenReturn(false);
        Mockito.when(subRepo.save(Mockito.any())).thenAnswer(i -> i.getArgument(0));
        Mockito.when(exRepo.save(Mockito.any())).thenAnswer(i -> {
            Exercise e = i.getArgument(0);
            e.setId(999L);
            return e;
        });
        service = new ExerciseSubmissionService(subRepo, exRepo, ruleRepo);
    }

    private ExerciseSubmissionDTO valid() {
        return ExerciseSubmissionDTO.builder()
                .name("哑铃酒杯深蹲")
                .category("力量").muscleGroup("腿").equipment("哑铃")
                .difficulty(2).metValue(5.0)
                .defaultSets(3).defaultReps("12 次")
                .description("双手抱哑铃于胸前，下蹲至大腿平行地面，膝盖与脚尖同向，起身时臀部发力。")
                .contraindication("膝关节")
                .sourceType("GUIDELINE")
                .sourceTitle("ACSM 运动测试与运动处方指南（第11版）")
                .sourceUrl("https://www.acsm.org/education-resources/books/guidelines-exercise-testing-prescription")
                .sourcePage("Chapter 7")
                .evidenceLevel("A")
                .build();
    }

    @Test
    void validSubmissionGoesPending() {
        ExerciseSubmission sub = service.submit(7L, "alice", valid());
        assertEquals(ExerciseSubmission.PENDING, sub.getStatus());
        assertNull(sub.getReviewNote());
    }

    @Test
    void missingSourceUrlIsRejectedWithReason() {
        ExerciseSubmissionDTO dto = valid();
        dto.setSourceUrl("   ");
        ExerciseSubmission sub = service.submit(7L, "alice", dto);
        assertEquals(ExerciseSubmission.REJECTED, sub.getStatus());
        assertTrue(sub.getReviewNote().contains("来源链接"), sub.getReviewNote());
        assertEquals("SYSTEM", sub.getReviewerName());
    }

    @Test
    void malformedUrlIsRejected() {
        ExerciseSubmissionDTO dto = valid();
        dto.setSourceUrl("acsm.org/guidelines");
        ExerciseSubmission sub = service.submit(7L, "alice", dto);
        assertEquals(ExerciseSubmission.REJECTED, sub.getStatus());
        assertTrue(sub.getReviewNote().contains("http"), sub.getReviewNote());
    }

    @Test
    void impossibleMetIsRejected() {
        ExerciseSubmissionDTO dto = valid();
        dto.setMetValue(45.0);
        ExerciseSubmission sub = service.submit(7L, "alice", dto);
        assertEquals(ExerciseSubmission.REJECTED, sub.getStatus());
        assertTrue(sub.getReviewNote().contains("MET"), sub.getReviewNote());
    }

    @Test
    void illegalEnumIsRejected() {
        ExerciseSubmissionDTO dto = valid();
        dto.setCategory("拉伸术");
        ExerciseSubmission sub = service.submit(7L, "alice", dto);
        assertEquals(ExerciseSubmission.REJECTED, sub.getStatus());
        assertTrue(sub.getReviewNote().contains("类别"), sub.getReviewNote());
    }

    @Test
    void duplicateNameIsRejected() {
        Mockito.when(exRepo.existsByNameIgnoreCase("哑铃酒杯深蹲")).thenReturn(true);
        ExerciseSubmission sub = service.submit(7L, "alice", valid());
        assertEquals(ExerciseSubmission.REJECTED, sub.getStatus());
        assertTrue(sub.getReviewNote().contains("同名"), sub.getReviewNote());
    }

    @Test
    void approveCreatesExerciseWithSourcing() {
        ExerciseSubmission sub = service.submit(7L, "alice", valid());
        sub.setId(11L);
        Mockito.when(subRepo.findById(11L)).thenReturn(Optional.of(sub));

        Exercise created = service.approve(11L, 1L, "dandan", "来源已核对");

        ArgumentCaptor<Exercise> captor = ArgumentCaptor.forClass(Exercise.class);
        Mockito.verify(exRepo).save(captor.capture());
        Exercise saved = captor.getValue();
        assertEquals("哑铃酒杯深蹲", saved.getName());
        assertEquals("APPROVED", saved.getReviewStatus(), "只有通过的才进动作库");
        assertNotNull(saved.getSourceUrl(), "入库必须保留来源链接");
        assertEquals("A", saved.getEvidenceLevel());
        assertEquals(7L, saved.getSubmittedBy());
        assertEquals(1L, saved.getReviewedBy());
        assertNotNull(saved.getLastVerifiedAt(), "入库时记录来源核验日期");
        assertEquals(999L, created.getId());
        assertEquals(ExerciseSubmission.APPROVED, sub.getStatus());
        assertEquals(999L, sub.getCreatedExerciseId());
    }

    @Test
    void rejectRequiresReasonAndBlocksDoubleReview() {
        ExerciseSubmission sub = service.submit(7L, "alice", valid());
        sub.setId(12L);
        Mockito.when(subRepo.findById(12L)).thenReturn(Optional.of(sub));

        assertThrows(BusinessException.class, () -> service.reject(12L, 1L, "dandan", " "),
                "驳回必须有理由");

        ExerciseSubmission rejected = service.reject(12L, 1L, "dandan", "来源链接打不开");
        assertEquals(ExerciseSubmission.REJECTED, rejected.getStatus());
        assertEquals("来源链接打不开", rejected.getReviewNote());

        assertThrows(BusinessException.class, () -> service.approve(12L, 1L, "dandan", null),
                "已处理的投稿不能再次通过");
    }

    @Test
    void listFiltersByStatus() {
        Mockito.when(subRepo.findByStatusOrderByIdDesc(Mockito.eq("PENDING"), Mockito.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        assertNotNull(service.list("PENDING", 0, 20));
        Mockito.verify(subRepo).findByStatusOrderByIdDesc(Mockito.eq("PENDING"), Mockito.any());
    }

    @Test
    void mineReturnsOwnSubmissions() {
        Mockito.when(subRepo.findByUserIdOrderByIdDesc(7L)).thenReturn(List.of());
        assertTrue(service.mine(7L).isEmpty());
    }
}