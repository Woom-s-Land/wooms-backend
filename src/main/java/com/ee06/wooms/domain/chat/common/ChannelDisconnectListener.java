package com.ee06.wooms.domain.chat.common;

import com.ee06.wooms.domain.chat.ChannelRepository;
import com.ee06.wooms.domain.chat.SessionRepository;
import com.ee06.wooms.domain.chat.entity.Channel;
import com.ee06.wooms.domain.chat.entity.Woom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelDisconnectListener {
    private final ChannelRepository channelRepository;
    private final SessionRepository sessionRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        log.info("Handling disconnect for STOMP session: {}", sessionId);

        try {
            Woom woom = sessionRepository.get(sessionId);
            if (woom != null && woom.getWoomsId() != null) {
                Channel channel = channelRepository.get(woom.getWoomsId());
                if (channel != null) {
                    channel.removeWoom(woom);
                    channelRepository.put(woom.getWoomsId(), channel);

                    messagingTemplate.convertAndSend(
                            "/ws/wooms/disconnect/" + woom.getWoomsId(),
                            woom
                    );

                }
            }

            sessionRepository.remove(sessionId);
        } catch (Exception e) {
            log.error("Error handling disconnect: ", e);
        }
    }
}