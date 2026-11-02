package com.ee06.wooms.domain.sketch.repository;

import com.ee06.wooms.domain.sketch.entity.SketchMural;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SketchMuralRepository extends JpaRepository<SketchMural, Long> {
    List<SketchMural> findByRoomIdOrderByIdDesc(String roomId);
}
