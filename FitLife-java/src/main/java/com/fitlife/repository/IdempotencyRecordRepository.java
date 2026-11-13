package com.fitlife.repository;

import com.fitlife.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByUserIdAndIdemKey(Long userId, String idemKey);

    void deleteByUserIdAndIdemKey(Long userId, String idemKey);
}