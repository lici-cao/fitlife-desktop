package com.fitlife.repository;

import com.fitlife.entity.DietPlanItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DietPlanItemRepository extends JpaRepository<DietPlanItem, Long> {

    List<DietPlanItem> findByPlanIdOrderByDayOfWeekAscSeqAsc(Long planId);

    List<DietPlanItem> findByPlanIdOrderByDayOfWeekAscMealAscSeqAsc(Long planId);

    void deleteByPlanId(Long planId);
}