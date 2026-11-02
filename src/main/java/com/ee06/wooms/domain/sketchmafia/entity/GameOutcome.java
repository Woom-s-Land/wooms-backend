package com.ee06.wooms.domain.sketchmafia.entity;

public enum GameOutcome {
    CITIZEN_WIN,
    MAFIA_WIN,
    MAFIA_STEAL_WIN, // caught mafia guessed the real topic and stole the win
    ABORTED
}
