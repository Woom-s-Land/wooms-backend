package com.ee06.wooms.domain.sketch.controller;

import com.ee06.wooms.domain.sketch.dto.MuralResponse;
import com.ee06.wooms.domain.sketch.repository.SketchMuralRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only gallery of a group's archived murals. Authenticated (not in SecurityConfig WHITE_LIST).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/sketch")
public class SketchMuralController {

    private final SketchMuralRepository sketchMuralRepository;

    @GetMapping("/{roomId}/murals")
    public List<MuralResponse> listMurals(@PathVariable("roomId") String roomId) {
        return sketchMuralRepository.findByRoomIdOrderByIdDesc(roomId).stream()
                .map(MuralResponse::from)
                .toList();
    }
}
