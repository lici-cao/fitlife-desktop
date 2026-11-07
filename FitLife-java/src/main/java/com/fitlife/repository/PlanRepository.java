package com.fitlife.repository;

import com.fitlife.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlanRepository extends JpaRepository<Plan, Long> {

    List<Plan> findByUserIdOrderByIdDesc(Long userId);

    Optional<Plan> findFirstByUserIdOrderByIdDesc(Long userId);

    Optional<Plan> findFirstByUserIdAndStatusOrderByIdDesc(Long userId, String status);
}
