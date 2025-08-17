package com.ee06.wooms.domain.chat.common;

import com.ee06.wooms.domain.chat.SessionRepository;
import com.ee06.wooms.domain.chat.entity.Woom;
import com.ee06.wooms.global.jwt.JWTUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Component
public class StompChannelInterceptor implements ChannelInterceptor {
    private final JWTUtil jwtUtil;
    private final SessionRepository sessionRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (acc == null) return message;

        if (StompCommand.CONNECT.equals(acc.getCommand())) {
            String sessionId = acc.getSessionId();

            // 개인 큐용 Principal (세션ID 기반)
            acc.setUser(new UsernamePasswordAuthenticationToken(sessionId, null));


            String token = (String) acc.getSessionAttributes().get(JwtHandshakeInterceptor.ATTR_JWT_TOKEN);

            boolean valid = false;
            if (token != null && !token.isBlank()) {
                try { valid = jwtUtil.validateToken(token); }
                catch (Exception e) { log.warn("JWT validate failed: {}", e.getMessage()); }
            }

            if (valid) {
                try {
                    String nickname = jwtUtil.getNickname(token);
                    Integer costume = Integer.valueOf(jwtUtil.getCostume(token));
                    // woomsId는 SUBSCRIBE 경로에서 확정
                    String channelUuid = jwtUtil.getChannelUuid(token);
                    sessionRepository.put(sessionId, new Woom(nickname, costume, UUID.fromString(channelUuid)));
                    log.info("Session bound: {} -> {}, {}", sessionId, nickname, channelUuid);
                } catch (Exception e) {
                    log.error("JWT parse error", e);
                }
            } else {
                // 자동 재연결 루프 방지
                // 필요 시 익명 사용자로 진행
            }
        }
        return message;
    }
}
