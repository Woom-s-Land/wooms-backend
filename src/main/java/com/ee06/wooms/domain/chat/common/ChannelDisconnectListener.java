package com.ee06.wooms.domain.chat.common;

import com.ee06.wooms.domain.chat.ChannelRepository;
import com.ee06.wooms.domain.chat.SessionRepository;
import com.ee06.wooms.domain.chat.dto.MoveMessage;
import com.ee06.wooms.domain.chat.entity.Channel;
import com.ee06.wooms.domain.chat.entity.Woom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.UUID;

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
        var principal = headerAccessor.getUser();
        try {
            Woom woom = sessionRepository.get(sessionId);
            log.info("{sessionId: {}", sessionId);
            log.info("{woomsId: {}", woom.getWoomsId());
            if (woom != null && woom.getWoomsId() != null) {
                Channel channel = channelRepository.get(woom.getWoomsId());
                log.info("channel: {}", channel);
                if (channel != null) {
                    channel.removeWoom(woom);
                    log.info("Removed woom: {}", woom.getWoomsId());
                    messagingTemplate.convertAndSend("/ws/wooms/disconnect/" + woom.getWoomsId(), woom);
                }
                channel.getWooms().forEach(tempWoom -> {
                    messagingTemplate.convertAndSendToUser(
                            principal.getName(),
                            "/queue/init",
                            MoveMessage.of(tempWoom)
                    );
                    log.info("개인 큐 발송 !");
                });
            }

            sessionRepository.remove(sessionId);
        } catch (Exception e) {
            log.error("Error handling disconnect: ", e);
        }
    }
}