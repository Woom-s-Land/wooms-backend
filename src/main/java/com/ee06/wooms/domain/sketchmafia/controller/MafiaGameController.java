package com.ee06.wooms.domain.sketchmafia.controller;

import com.ee06.wooms.domain.sketchmafia.dto.inbound.ChatRequest;
import com.ee06.wooms.domain.sketchmafia.dto.inbound.GuessRequest;
import com.ee06.wooms.domain.sketchmafia.dto.inbound.JoinRequest;
import com.ee06.wooms.domain.sketchmafia.dto.inbound.StartGameRequest;
import com.ee06.wooms.domain.sketchmafia.dto.inbound.SubmitDrawingRequest;
import com.ee06.wooms.domain.sketchmafia.dto.inbound.VoteRequest;
import com.ee06.wooms.domain.sketchmafia.engine.MafiaGameEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

/**
 * STOMP inbound handlers for Drawing-Mafia. Publish destinations are under the
 * app prefix /ws/send (so e.g. /ws/send/sketchmafia/join/{roomId}). The sender's
 * STOMP sessionId comes from the simpSessionId header.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class MafiaGameController {

    private final ObjectMapper objectMapper;
    private final MafiaGameEngine engine;

    @MessageMapping("/sketchmafia/join/{roomId}")
    public void join(@DestinationVariable("roomId") String roomId,
                     @Header("simpSessionId") String sessionId,
                     String content) {
        JoinRequest req = read(content, JoinRequest.class);
        if (req == null) return;
        engine.join(roomId, sessionId, req.getNickname(), req.getCostume());
    }

    @MessageMapping("/sketchmafia/start/{roomId}")
    public void start(@DestinationVariable("roomId") String roomId,
                      @Header("simpSessionId") String sessionId,
                      String content) {
        StartGameRequest req = read(content, StartGameRequest.class);
        engine.start(roomId, sessionId, req != null ? req.getDrawSeconds() : null);
    }

    @MessageMapping("/sketchmafia/submit/{roomId}")
    public void submit(@DestinationVariable("roomId") String roomId,
                       @Header("simpSessionId") String sessionId,
                       String content) {
        SubmitDrawingRequest req = read(content, SubmitDrawingRequest.class);
        if (req == null) return;
        engine.submit(roomId, sessionId, req.getPixels());
    }

    @MessageMapping("/sketchmafia/vote/{roomId}")
    public void vote(@DestinationVariable("roomId") String roomId,
                     @Header("simpSessionId") String sessionId,
                     String content) {
        VoteRequest req = read(content, VoteRequest.class);
        if (req == null) return;
        engine.vote(roomId, sessionId, req.getTarget());
    }

    @MessageMapping("/sketchmafia/chat/{roomId}")
    public void chat(@DestinationVariable("roomId") String roomId,
                     @Header("simpSessionId") String sessionId,
                     String content) {
        ChatRequest req = read(content, ChatRequest.class);
        if (req == null) return;
        engine.chat(roomId, sessionId, req.getMessage());
    }

    @MessageMapping("/sketchmafia/guess/{roomId}")
    public void guess(@DestinationVariable("roomId") String roomId,
                      @Header("simpSessionId") String sessionId,
                      String content) {
        GuessRequest req = read(content, GuessRequest.class);
        if (req == null) return;
        engine.guess(roomId, sessionId, req.getWord());
    }

    private <T> T read(String content, Class<T> type) {
        if (content == null || content.isBlank()) {
            try { return type.getDeclaredConstructor().newInstance(); } // empty body -> defaults
            catch (Exception e) { return null; }
        }
        try {
            return objectMapper.readValue(content, type);
        } catch (Exception e) {
            log.warn("[sketchmafia] bad payload for {}: {}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }
}
