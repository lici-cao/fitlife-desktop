package com.fitlife.repository;

import com.fitlife.entity.PlanDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlanDetailRepository extends JpaRepository<PlanDetail, Long> {

    List<PlanDetail> findByPlanIdOrderByDayOfWeekAscSeqAsc(Long planId);

    void deleteByPlanId(Long planId);
}
