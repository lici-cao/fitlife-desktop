package com.fitlife.repository;

import com.fitlife.entity.Exercise;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ExerciseRepository extends JpaRepository<Exercise, Long> {

    List<Exercise> findByCategory(String category);

    List<Exercise> findByMuscleGroup(String muscleGroup);

    /**
     * 规则引擎第一层筛选：难度不超过用户经验等级，且器械在用户可用器械范围内。
     * 禁忌症过滤在 Service 层完成（因为需要按逗号拆分匹配）。
     */
    @Query("SELECT e FROM Exercise e WHERE e.difficulty <= :level AND e.equipment IN :equipments ORDER BY e.category, e.muscleGroup, e.id")
    List<Exercise> findCandidates(@Param("level") Integer level,
                                  @Param("equipments") Collection<String> equipments);
}
