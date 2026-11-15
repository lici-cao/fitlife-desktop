package com.fitlife.repository;

import com.fitlife.entity.PlanAdherence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PlanAdherenceRepository extends JpaRepository<PlanAdherence, Long> {

    Optional<PlanAdherence> findByUserIdAndPlanIdAndDate(Long userId, Long planId, LocalDate date);

    List<PlanAdherence> findByUserIdAndDateBetweenOrderByDateAsc(Long userId, LocalDate start, LocalDate end);

    List<PlanAdherence> findByUserIdAndPlanIdOrderByDateAsc(Long userId, Long planId);

    Optional<PlanAdherence> findFirstByUserIdOrderByDateDesc(Long userId);

    /** 区间内应做动作数之和（只统计有计划的日子） */
    @Query("SELECT COALESCE(SUM(a.plannedItems), 0) FROM PlanAdherence a "
            + "WHERE a.userId = :userId AND a.date BETWEEN :start AND :end")
    long sumPlanned(@Param("userId") Long userId,
                    @Param("start") LocalDate start,
                    @Param("end") LocalDate end);

    @Query("SELECT COALESCE(SUM(a.doneItems), 0) FROM PlanAdherence a "
            + "WHERE a.userId = :userId AND a.date BETWEEN :start AND :end")
    long sumDone(@Param("userId") Long userId,
                 @Param("start") LocalDate start,
                 @Param("end") LocalDate end);

    @Query("SELECT COALESCE(SUM(a.skippedItems), 0) FROM PlanAdherence a "
            + "WHERE a.userId = :userId AND a.date BETWEEN :start AND :end")
    long sumSkipped(@Param("userId") Long userId,
                    @Param("start") LocalDate start,
                    @Param("end") LocalDate end);

    /** 有疼痛上报的天数 */
    @Query("SELECT COUNT(a) FROM PlanAdherence a "
            + "WHERE a.userId = :userId AND a.date BETWEEN :start AND :end AND a.painFlag = 1")
    long countPainDays(@Param("userId") Long userId,
                       @Param("start") LocalDate start,
                       @Param("end") LocalDate end);
}