package com.fitlife.repository;

import com.fitlife.entity.WorkoutRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface WorkoutRecordRepository extends JpaRepository<WorkoutRecord, Long> {
    List<WorkoutRecord> findByUserIdAndDateBetween(Long userId, LocalDate startDate, LocalDate endDate);
    List<WorkoutRecord> findByUserId(Long userId);
}
