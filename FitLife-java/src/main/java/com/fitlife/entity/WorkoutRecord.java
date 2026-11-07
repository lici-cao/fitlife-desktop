package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDate;

@Entity
@Table(name = "workout_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkoutRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "exercise_id", nullable = false)
    private String exerciseId;

    @Column(name = "exercise_name")
    private String exerciseName;

    @Column(nullable = false)
    private Integer duration;

    @Column(name = "calories_burned")
    private Double caloriesBurned;
}
