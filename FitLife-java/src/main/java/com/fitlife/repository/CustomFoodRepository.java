package com.fitlife.repository;

import com.fitlife.entity.CustomFood;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomFoodRepository extends JpaRepository<CustomFood, Long> {
    List<CustomFood> findByUserId(Long userId);

    /** 归属校验：该自定义食物是否属于该用户（P1 水平越权防护） */
    boolean existsByIdAndUserId(Long id, Long userId);
}
