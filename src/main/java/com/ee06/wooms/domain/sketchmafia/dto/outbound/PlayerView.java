package com.ee06.wooms.domain.sketchmafia.dto.outbound;

import lombok.Builder;
import lombok.Getter;

/**
 * Public per-player info. Deliberately has NO role/word field so secret state
 * can never leak through a broadcast.
 */
@Getter
@Builder
public class PlayerView {
    private String nickname;
    private Integer costume;
    private boolean connected;
    private boolean host;
    private boolean submitted; // submitted a drawing this round
    private boolean voted;     // has cast a vote
    private int score;         // cumulative score this room
}
