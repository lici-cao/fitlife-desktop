package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDate;

@Entity
@Table(name = "food_intakes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FoodIntake {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private Double calories;

    @Column(name = "food_id")
    private String foodId;

    private Double amount;
}
