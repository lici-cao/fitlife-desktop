package com.fitlife.repository;

import com.fitlife.entity.DailyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyRecordRepository extends JpaRepository<DailyRecord, Long> {
    Optional<DailyRecord> findByUserIdAndDate(Long userId, LocalDate date);
    List<DailyRecord> findByUserIdAndDateBetween(Long userId, LocalDate startDate, LocalDate endDate);
    List<DailyRecord> findByUserId(Long userId);
}
