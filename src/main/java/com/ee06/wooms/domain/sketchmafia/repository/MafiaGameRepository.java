package com.ee06.wooms.domain.sketchmafia.repository;

import com.ee06.wooms.domain.sketchmafia.entity.MafiaGame;
import com.ee06.wooms.domain.sketchmafia.topic.TopicService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Holds the live {@link MafiaGame} per room (in memory only). Mirrors
 * {@code ChannelRepository}/{@code SketchSessionRepository}. Also keeps a
 * reverse sessionId -> roomId index so disconnect handling can find the game.
 */
@Component
@RequiredArgsConstructor
public class MafiaGameRepository {

    private final TopicService topicService;

    private final ConcurrentMap<String, MafiaGame> games = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> sessionRoom = new ConcurrentHashMap<>();

    public MafiaGame getOrCreate(String roomId) {
        return games.computeIfAbsent(roomId, id -> new MafiaGame(id, topicService.newBag()));
    }

    public MafiaGame get(String roomId) {
        return games.get(roomId);
    }

    public void remove(String roomId) {
        games.remove(roomId);
    }

    // --- reverse index: which room a STOMP session is playing in ---
    public void bindSession(String sessionId, String roomId) {
        sessionRoom.put(sessionId, roomId);
    }

    public String roomOfSession(String sessionId) {
        return sessionRoom.get(sessionId);
    }

    public void unbindSession(String sessionId) {
        sessionRoom.remove(sessionId);
    }
}
