package com.fitlife.repository;

import com.fitlife.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlanRepository extends JpaRepository<Plan, Long> {

    List<Plan> findByUserIdOrderByIdDesc(Long userId);

    /** 历史回退用：按 id 升序，便于取「当天之前最近创建的一版」 */
    List<Plan> findByUserIdOrderByIdAsc(Long userId);

    Optional<Plan> findFirstByUserIdOrderByIdDesc(Long userId);

    Optional<Plan> findFirstByUserIdAndStatusOrderByIdDesc(Long userId, String status);

    /** 归属校验用：不查实体，避免把整份计划（含明细）读进内存。 */
    boolean existsByIdAndUserId(Long id, Long userId);

    /** 版本链：同用户当前最大版本号（无计划时为 0） */
    @Query("SELECT COALESCE(MAX(p.version), 0) FROM Plan p WHERE p.userId = :userId")
    int findMaxVersion(@Param("userId") Long userId);
}
