package com.ee06.wooms.domain.sketch.controller;

import com.ee06.wooms.domain.sketch.SketchSessionRepository;
import com.ee06.wooms.domain.sketch.dto.StrokeMessage;
import com.ee06.wooms.domain.sketch.entity.SketchMural;
import com.ee06.wooms.domain.sketch.entity.SketchSession;
import com.ee06.wooms.domain.sketch.repository.SketchMuralRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * Real-time shared pixel canvas. Follows the same STOMP conventions as ChatController:
 * client publishes to /ws/send/sketch/draw/{woomsId}, server broadcasts to /ws/wooms/sketch/draw/{woomsId}.
 * Initial canvas snapshot for late joiners is handled by {@code SketchSubscribeListener}.
 */
@Slf4j
@RequiredArgsConstructor
@Controller
public class SketchController {

    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate template;
    private final SketchSessionRepository sketchSessionRepository;
    private final SketchMuralRepository sketchMuralRepository;

    @MessageMapping("/sketch/draw/{roomId}")
    public void draw(@DestinationVariable("roomId") String roomId,
                     String content) throws Exception {
        StrokeMessage stroke = objectMapper.readValue(content, StrokeMessage.class);

        sketchSessionRepository.get(roomId).apply(stroke);
        sketchSessionRepository.markDirty(roomId);

        template.convertAndSend("/ws/wooms/sketch/draw/" + roomId, stroke);
    }

    /**
     * Archive the current mural into the group's gallery and start a fresh blank wall.
     * Broadcasts a RESET so every connected client clears their live canvas.
     */
    @MessageMapping("/sketch/save/{roomId}")
    public void save(@DestinationVariable("roomId") String roomId,
                     String content) {
        SketchSession session = sketchSessionRepository.get(roomId);
        if (session.isEmpty()) return; // nothing to archive

        String savedBy = null;
        try {
            savedBy = objectMapper.readValue(content, StrokeMessage.class).getNickname();
        } catch (Exception ignored) { /* nickname optional */ }

        sketchMuralRepository.save(SketchMural.builder()
                .roomId(roomId)
                .savedBy(savedBy)
                .pixels(session.serialize())
                .build());

        session.reset();
        sketchSessionRepository.markDirty(roomId);

        template.convertAndSend("/ws/wooms/sketch/draw/" + roomId,
                StrokeMessage.builder().tool("RESET").nickname(savedBy).build());
    }
}
