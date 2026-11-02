package com.ee06.wooms.domain.sketch.entity;

import com.ee06.wooms.global.audit.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Persistent snapshot of one group's cooperative pixel mural.
 * Stores the canvas as a compact CSV of palette indices (see SketchSession#serialize),
 * so the mural survives restarts and accumulates over time. One row per roomId.
 */
@Entity
@Table(name = "sketch_walls")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SketchWall extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sketch_wall_id")
    private Long id;

    @Column(name = "room_id", unique = true, nullable = false)
    private String roomId;

    @Column(name = "pixels", columnDefinition = "TEXT")
    private String pixels;

    public void updatePixels(String pixels) {
        this.pixels = pixels;
    }
}
