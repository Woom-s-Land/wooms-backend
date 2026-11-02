package com.ee06.wooms.domain.sketchmafia.dto.outbound;

import lombok.Builder;
import lombok.Getter;

/**
 * Secret role + word. Sent ONLY to one player via convertAndSendToUser
 * (/user/queue/sketchmafia/role). Never broadcast.
 */
@Getter
@Builder
public class RoleAssignment {
    private String role;     // "CITIZEN" | "MAFIA"
    private String word;     // citizen=topic, mafia=decoy
    private String category;
    private boolean mafia;
    private String note;     // hint shown to the player
}
