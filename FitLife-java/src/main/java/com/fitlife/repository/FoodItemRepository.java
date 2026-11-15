package com.fitlife.repository;

import com.fitlife.entity.FoodItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FoodItemRepository extends JpaRepository<FoodItem, Long> {

    List<FoodItem> findByEnabledOrderByCategoryAscIdAsc(Integer enabled);

    List<FoodItem> findByCategoryAndEnabledOrderByIdAsc(String category, Integer enabled);

    List<FoodItem> findByNameContainingAndEnabledOrderByIdAsc(String keyword, Integer enabled);

    Optional<FoodItem> findByName(String name);

    List<FoodItem> findByCategoryInAndEnabled(List<String> categories, Integer enabled);
}