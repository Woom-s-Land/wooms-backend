package com.ee06.wooms.domain.sketch.dto;

import com.ee06.wooms.domain.sketch.entity.SketchMural;
import com.ee06.wooms.domain.sketch.entity.SketchSession;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Archived mural for the gallery. Pixels are returned as a decoded grid (same shape
 * as CanvasSnapshot) so the client reuses its canvas-render logic for thumbnails.
 */
@Getter
@Builder
public class MuralResponse {
    private Long id;
    private String savedBy;
    private LocalDateTime savedAt;
    private int size;
    private int[][] pixels;

    public static MuralResponse from(SketchMural mural) {
        SketchSession decoder = new SketchSession();
        decoder.deserialize(mural.getPixels());
        return MuralResponse.builder()
                .id(mural.getId())
                .savedBy(mural.getSavedBy())
                .savedAt(mural.getCreatedDate())
                .size(SketchSession.SIZE)
                .pixels(decoder.snapshot())
                .build();
    }
}
