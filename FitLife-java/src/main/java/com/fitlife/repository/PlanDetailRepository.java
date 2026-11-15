package com.fitlife.repository;

import com.fitlife.entity.PlanDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlanDetailRepository extends JpaRepository<PlanDetail, Long> {

    List<PlanDetail> findByPlanIdOrderByDayOfWeekAscSeqAsc(Long planId);

    void deleteByPlanId(Long planId);

    /**
     * 归属校验：该明细是否属于该用户的某一版计划（含历史版本）。
     *
     * <p>只查存在性、不装载实体，避免把整份计划读进内存。
     */
    @Query("select count(d) > 0 from PlanDetail d, Plan p "
            + "where d.id = :detailId and d.planId = p.id and p.userId = :userId")
    boolean existsByIdAndOwner(@Param("detailId") Long detailId, @Param("userId") Long userId);
}