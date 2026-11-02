package com.ee06.wooms.domain.realtime;

import com.ee06.wooms.domain.sketch.dto.CanvasSnapshot;
import com.ee06.wooms.domain.sketch.dto.MuralResponse;
import com.ee06.wooms.domain.users.dto.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/realtime/rooms/{roomId}/wall")
@RequiredArgsConstructor
public class RealtimeWallController {

    private final RealtimeWallService realtimeWallService;

    @GetMapping
    public CanvasSnapshot get(@AuthenticationPrincipal CustomUserDetails current,
                              @PathVariable String roomId) {
        return realtimeWallService.get(current, roomId);
    }

    @PutMapping
    public CanvasSnapshot put(@AuthenticationPrincipal CustomUserDetails current,
                              @PathVariable String roomId,
                              @RequestBody RealtimeWallService.WallRequest request) {
        return realtimeWallService.put(current, roomId, request);
    }

    @PostMapping("/archive")
    public MuralResponse archive(@AuthenticationPrincipal CustomUserDetails current,
                                 @PathVariable String roomId,
                                 @RequestBody RealtimeWallService.WallRequest request) {
        return realtimeWallService.archive(current, roomId, request);
    }
}
