package com.ee06.wooms.domain.sketchmafia.dto.outbound;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * Full reveal at RESULT — this is the ONLY place role/word become public.
 */
@Getter
@Builder
public class ResultBroadcast {
    private String topic;
    private String decoy;
    private String category;
    private String mafiaNickname;
    private String accusedNickname;  // most-voted (null if tie/none)
    private boolean tie;
    private String outcome;          // CITIZEN_WIN | MAFIA_WIN | ABORTED
    private Map<String, Integer> voteTally; // targetNickname -> votes
    private Map<String, String> votes;      // voterNickname -> targetNickname
    private Map<String, Integer> scores;    // nickname -> cumulative score
    private List<Role> roles;

    @Getter
    @Builder
    public static class Role {
        private String nickname;
        private String role;  // CITIZEN | MAFIA
        private String word;
    }
}
