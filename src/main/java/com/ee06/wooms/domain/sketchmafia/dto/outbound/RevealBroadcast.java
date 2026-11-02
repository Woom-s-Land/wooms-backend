package com.ee06.wooms.domain.sketchmafia.dto.outbound;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** All players' drawings, shown together at REVEAL. No role/word. */
@Getter
@Builder
public class RevealBroadcast {
    private int size;
    private List<Entry> drawings;

    @Getter
    @Builder
    public static class Entry {
        private String nickname;
        private int[][] pixels;
        private boolean submitted;
    }
}
