package com.ee06.wooms.domain.chat.common;

import com.ee06.wooms.domain.chat.ChannelRepository;
import com.ee06.wooms.domain.chat.SessionRepository;
import com.ee06.wooms.domain.chat.entity.Channel;
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
import org.springframework.messaging.support.NativeMessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Component
public class StompChannelInterceptor implements ChannelInterceptor {
    private final JWTUtil jwtUtil;
    private final ChannelRepository channelRepository;
    private final SessionRepository sessionRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            // STOMP 연결 시점
            String sessionId = accessor.getSessionId();

            log.info("STOMP Connection - Session ID: {}", sessionId);
            Object nativeHeadersObj = accessor.getMessageHeaders().get(NativeMessageHeaderAccessor.NATIVE_HEADERS);
            if (nativeHeadersObj instanceof java.util.Map<?, ?> map) {
                log.info("일단 들어옴");
                @SuppressWarnings("unchecked")
                var nativeHeaders = (java.util.Map<String, java.util.List<String>>) map;
                nativeHeaders.forEach((k, v) -> log.info("STOMP native header [{}] = {}", k, v));
            }

            // Principal.getName() == sessionId 로 사용
            accessor.setUser(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(sessionId, null));

            String token = accessor.getFirstNativeHeader("Authorization");
            if (token == null) token = accessor.getFirstNativeHeader("authorization");

            if (token != null && jwtUtil.validateToken(token)) {
                try {
                    String nickname = jwtUtil.getNickname(token);
                    Integer costume = Integer.valueOf(jwtUtil.getCostume(token));
                    String channelUuidStr = jwtUtil.getChannelUuid(token);
                    log.info("channelUUidStr: {}", channelUuidStr);
                    // Woom 객체 생성 및 저장
                    Woom woom = new Woom(nickname, costume, null);
                    if (channelUuidStr != null && !channelUuidStr.isEmpty()) {
                        UUID channelUuid = UUID.fromString(channelUuidStr);
                        log.info("channelUUid: {}", channelUuid);
                        woom.setWoomsId(channelUuid);

                        Channel channels = channelRepository.get(channelUuid);
                        channels.addWoom(woom);
                        channelRepository.put(channelUuid, channels);
                    }

                    // STOMP 세션 ID로 저장
                    sessionRepository.put(sessionId, woom);
                    log.info("Stored user {} with STOMP session: {}", nickname, sessionId);
                } catch (Exception e) {
                    log.error("Error processing STOMP connection: ", e);
                }
            }
        }
        return message;
    }
}