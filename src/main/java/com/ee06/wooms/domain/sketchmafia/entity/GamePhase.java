package com.ee06.wooms.domain.sketchmafia.entity;

/**
 * Phases of one Drawing-Mafia round. {@code defaultSeconds} is the auto-advance
 * timer (0 = no timer; waits for a player action like host-start or restart).
 */
public enum GamePhase {
    LOBBY(0),
    ASSIGN(3),
    DRAW(20),
    REVEAL(6),
    DISCUSS(60),
    VOTE(20),
    MAFIA_GUESS(15),
    RESULT(0);

    public final int defaultSeconds;

    GamePhase(int defaultSeconds) {
        this.defaultSeconds = defaultSeconds;
    }
}
