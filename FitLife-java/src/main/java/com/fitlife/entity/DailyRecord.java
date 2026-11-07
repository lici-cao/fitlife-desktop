package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDate;

@Entity
@Table(name = "daily_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "food_intake")
    private Double foodIntake;

    @Column(name = "exercise_burn")
    private Double exerciseBurn;

    @Column(name = "target_calories")
    private Double targetCalories;

    private Double weight;
}
