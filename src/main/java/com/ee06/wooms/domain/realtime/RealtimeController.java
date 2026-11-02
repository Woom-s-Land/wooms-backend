package com.ee06.wooms.domain.realtime;

import com.ee06.wooms.domain.users.dto.CustomUserDetails;
import com.ee06.wooms.global.jwt.JWTUtil;
import com.ee06.wooms.global.util.CookieUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/realtime/rooms")
@RequiredArgsConstructor
public class RealtimeController {

    private final RealtimeSessionService realtimeSessionService;
    private final JWTUtil jwtUtil;

    public record SessionResponse(String roomId, String userUuid, String nickname, Integer costume, long expiresAt) {}

    @GetMapping("/{roomId}/session")
    public SessionResponse session(
            @AuthenticationPrincipal CustomUserDetails current,
            @PathVariable String roomId,
            HttpServletRequest request) {
        RealtimeSessionService.SessionInfo session = realtimeSessionService.session(current, roomId);
        String token = CookieUtils.getCookie(request, "Authorization");
        if (token == null || token.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        return new SessionResponse(session.roomId(), session.userUuid(), session.nickname(), session.costume(), jwtUtil.getExpiresAt(token));
    }
}
