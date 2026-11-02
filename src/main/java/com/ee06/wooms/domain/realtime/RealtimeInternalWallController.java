package com.ee06.wooms.domain.realtime;

import com.ee06.wooms.domain.sketch.dto.CanvasSnapshot;
import com.ee06.wooms.domain.wooms.repository.WoomsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth/realtime/rooms/{roomId}/wall")
@RequiredArgsConstructor
public class RealtimeInternalWallController {
    private final RealtimeWallService wallService;
    private final WoomsRepository woomsRepository;

    @Value("${WOOMS_REALTIME_INTERNAL_SECRET:}")
    private String internalSecret;

    @PutMapping
    public CanvasSnapshot put(@PathVariable String roomId,
                              @RequestHeader(value = "X-Wooms-Realtime-Key", required = false) String suppliedKey,
                              @RequestBody RealtimeWallService.WallRequest request) {
        if (internalSecret == null || internalSecret.length() < 32) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Internal realtime key is not configured");
        }
        if (suppliedKey == null || !MessageDigest.isEqual(
                internalSecret.getBytes(StandardCharsets.UTF_8), suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        final UUID inviteCode;
        try {
            inviteCode = UUID.fromString(roomId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid room UUID", exception);
        }
        if (woomsRepository.findByInviteCode(inviteCode).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return wallService.putFromSocket(inviteCode.toString(), request);
    }
}
