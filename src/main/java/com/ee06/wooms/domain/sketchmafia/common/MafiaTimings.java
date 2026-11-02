package com.ee06.wooms.domain.sketchmafia.common;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-phase durations (seconds), overridable via `sketchmafia.*-seconds` properties.
 * Defaults match the original GamePhase values; tests/host can shorten them.
 */
@Getter
@Component
public class MafiaTimings {
    @Value("${sketchmafia.assign-seconds:3}")
    private int assignSeconds;
    @Value("${sketchmafia.draw-seconds:20}")
    private int drawSeconds;
    @Value("${sketchmafia.reveal-seconds:6}")
    private int revealSeconds;
    @Value("${sketchmafia.discuss-seconds:60}")
    private int discussSeconds;
    @Value("${sketchmafia.vote-seconds:20}")
    private int voteSeconds;
    @Value("${sketchmafia.guess-seconds:15}")
    private int guessSeconds;
}
