package com.ee06.wooms.domain.sketch.common;

import com.ee06.wooms.domain.sketch.SketchSessionRepository;
import com.ee06.wooms.domain.sketch.dto.CanvasSnapshot;
import com.ee06.wooms.domain.sketch.entity.SketchSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;

/**
 * When a user subscribes to a drawing room's broadcast topic, push the current
 * canvas snapshot to that user's private queue so late joiners see existing pixels.
 * Mirrors {@code ChannelSubscribeListener}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SketchSubscribeListener {

    private static final String PREFIX = "/ws/wooms/sketch/draw/";

    private final SketchSessionRepository sketchSessionRepository;
    private final SimpMessagingTemplate template;

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        Principal principal = accessor.getUser();
        if (destination == null || principal == null) return;
        if (!destination.startsWith(PREFIX)) return;

        String roomId = destination.substring(PREFIX.length());
        SketchSession session = sketchSessionRepository.get(roomId);

        template.convertAndSendToUser(
                principal.getName(),
                "/queue/sketch/init",
                CanvasSnapshot.builder()
                        .size(SketchSession.SIZE)
                        .pixels(session.snapshot())
                        .build()
        );
    }
}
