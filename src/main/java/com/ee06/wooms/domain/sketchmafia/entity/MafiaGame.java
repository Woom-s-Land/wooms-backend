package com.ee06.wooms.domain.sketchmafia.entity;

import com.ee06.wooms.domain.sketchmafia.topic.TopicPair;
import com.ee06.wooms.domain.sketchmafia.topic.TopicService;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory state for one room's Drawing-Mafia game (volatile, no DB).
 * ALL access goes through {@link #lock}; the engine holds it for every
 * transition, timer callback and disconnect so state stays consistent.
 */
@Getter
@Setter
public class MafiaGame {

    public static final int GRID_SIZE = 32;
    public static final int MIN_PLAYERS = 3;
    public static final int MAX_PLAYERS = 8;

    private final String roomId;
    private final transient ReentrantLock lock = new ReentrantLock();
    private final transient TopicService.Bag topicBag;

    /** nickname -> player, in join order (host succession order). */
    private final Map<String, MafiaPlayer> players = new LinkedHashMap<>();

    private GamePhase phase = GamePhase.LOBBY;
    private int round = 0;
    private String hostNickname;

    private TopicPair topicPair;        // secret until RESULT
    private String mafiaNickname;       // secret until RESULT
    private int drawSeconds = GamePhase.DRAW.defaultSeconds;

    private long phaseEndsAtEpochMs;    // 0 = no timer
    private transient ScheduledFuture<?> phaseTimer;

    /** voterNickname -> targetNickname (VOTE phase). */
    private final Map<String, String> votes = new LinkedHashMap<>();

    /** nickname -> cumulative score across rounds in this room (survives rounds). */
    private final Map<String, Integer> scores = new LinkedHashMap<>();
    private GameOutcome outcome;
    private String accusedNickname;
    private boolean tie;

    public MafiaGame(String roomId, TopicService.Bag topicBag) {
        this.roomId = roomId;
        this.topicBag = topicBag;
    }

    public List<MafiaPlayer> connectedPlayers() {
        List<MafiaPlayer> list = new ArrayList<>();
        for (MafiaPlayer p : players.values()) {
            if (p.isConnected()) list.add(p);
        }
        return list;
    }

    public MafiaPlayer player(String nickname) {
        return players.get(nickname);
    }
}
