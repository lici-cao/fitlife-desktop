package com.fitlife.repository;

import com.fitlife.entity.CustomFood;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomFoodRepository extends JpaRepository<CustomFood, Long> {
    List<CustomFood> findByUserId(Long userId);
}
