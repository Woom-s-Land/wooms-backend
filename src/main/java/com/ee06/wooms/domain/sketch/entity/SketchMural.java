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
 * An archived (completed) mural in a group's gallery. Many per roomId.
 * Stores the canvas as a CSV grid (same format as SketchWall) so thumbnails
 * can be re-rendered on the client. createdDate (from BaseTimeEntity) = saved time.
 */
@Entity
@Table(name = "sketch_murals")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SketchMural extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sketch_mural_id")
    private Long id;

    @Column(name = "room_id", nullable = false)
    private String roomId;

    @Column(name = "saved_by")
    private String savedBy;

    @Column(name = "pixels", columnDefinition = "TEXT")
    private String pixels;
}
