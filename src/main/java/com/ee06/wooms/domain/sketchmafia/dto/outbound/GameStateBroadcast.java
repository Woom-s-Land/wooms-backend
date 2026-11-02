package com.ee06.wooms.domain.sketchmafia.dto.outbound;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Public game state, sent on every phase change and roster change to
 * /ws/wooms/sketchmafia/state/{roomId}. Contains NO role/word.
 * Clients drive their countdown from (endsAt - serverNow).
 */
@Getter
@Builder
public class GameStateBroadcast {
    private String phase;
    private int round;
    private List<PlayerView> players;
    private String host;
    private int minPlayers;
    private int maxPlayers;
    private Long endsAt;     // epoch ms when current phase auto-advances (null = no timer)
    private Long serverNow;  // server clock for countdown sync
    private String message;  // optional status note (e.g. "마피아가 나가 게임이 중단됐어요")
}
