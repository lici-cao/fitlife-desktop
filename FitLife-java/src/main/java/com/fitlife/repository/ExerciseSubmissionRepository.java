package com.fitlife.repository;

import com.fitlife.entity.ExerciseSubmission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExerciseSubmissionRepository extends JpaRepository<ExerciseSubmission, Long> {

    Page<ExerciseSubmission> findByStatusOrderByIdDesc(String status, Pageable pageable);

    Page<ExerciseSubmission> findAllByOrderByIdDesc(Pageable pageable);

    List<ExerciseSubmission> findByUserIdOrderByIdDesc(Long userId);

    long countByStatus(String status);
}