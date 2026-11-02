package com.ee06.wooms.domain.sketchmafia.entity;

import lombok.Getter;
import lombok.Setter;

/**
 * One player's mutable in-game state. Keyed by nickname within a room
 * (consistent with the chat domain's {@code Channel}, which assumes unique
 * nickname per room). {@code sessionId} is kept for private (per-user) sends
 * and disconnect handling. Role/word are secret until RESULT.
 */
@Getter
@Setter
public class MafiaPlayer {

    private final String nickname;
    private final Integer costume;

    private String sessionId;     // current STOMP session (updated on re-join)
    private boolean connected = true;

    private PlayerRole role;      // assigned at ASSIGN, secret until RESULT
    private String word;          // citizen=topic, mafia=decoy; secret until RESULT

    private int[][] grid;         // submitted drawing (null until submitted)
    private boolean submitted;

    private String votedFor;      // nickname this player voted for (null = not yet)

    public MafiaPlayer(String nickname, Integer costume, String sessionId) {
        this.nickname = nickname;
        this.costume = costume;
        this.sessionId = sessionId;
    }

    public boolean isMafia() {
        return role == PlayerRole.MAFIA;
    }

    /** Reset per-round state (kept across rounds: nickname, costume, sessionId, connected). */
    public void resetForNewRound() {
        this.role = null;
        this.word = null;
        this.grid = null;
        this.submitted = false;
        this.votedFor = null;
    }
}
