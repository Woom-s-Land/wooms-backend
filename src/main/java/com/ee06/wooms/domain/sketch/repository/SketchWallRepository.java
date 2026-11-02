package com.ee06.wooms.domain.sketch.repository;

import com.ee06.wooms.domain.sketch.entity.SketchWall;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SketchWallRepository extends JpaRepository<SketchWall, Long> {
    Optional<SketchWall> findByRoomId(String roomId);
}
