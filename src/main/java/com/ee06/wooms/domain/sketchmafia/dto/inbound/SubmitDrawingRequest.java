package com.ee06.wooms.domain.sketchmafia.dto.inbound;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A player's final drawing for the round: a SIZE×SIZE grid of palette indices (-1 = empty). */
@Getter
@Setter
@NoArgsConstructor
public class SubmitDrawingRequest {
    private int[][] pixels;
}
