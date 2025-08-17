package com.ee06.wooms.domain.chat.common;

import com.ee06.wooms.domain.chat.ChannelRepository;
import com.ee06.wooms.domain.chat.dto.MoveMessage;
import com.ee06.wooms.domain.chat.entity.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChannelSubscribeListener {

    private final ChannelRepository channelRepository;
    private final SimpMessagingTemplate template;

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        var principal = accessor.getUser();
        if (destination == null || principal == null) return;
        log.info("destination : {}", destination);
        final String prefix = "/ws/wooms/move/";
        if (!destination.startsWith(prefix)) return;

        String idStr = destination.substring(prefix.length());
        UUID woomsId = UUID.fromString(idStr);
        Channel channel = channelRepository.get(woomsId);
        channel.getWooms().forEach(woom -> {
            template.convertAndSendToUser(
                    principal.getName(),
                    "/queue/init",
                    MoveMessage.of(woom)
            );
        });
    }
}
