package com.ee06.wooms.domain.sketch.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Full canvas state sent to a user who just joined the drawing room,
 * so late joiners see what was already drawn.
 */
@Getter
@AllArgsConstructor
@Builder
public class CanvasSnapshot {
    private int size;
    private int[][] pixels;
}
