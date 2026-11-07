package com.fitlife.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Entity
@Table(name = "custom_foods")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomFood {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Double calories;

    private Double protein;
    private Double carbs;
    private Double fat;

    @Column(name = "serving_size")
    private Double servingSize;

    private String unit;
    private String category;
}
