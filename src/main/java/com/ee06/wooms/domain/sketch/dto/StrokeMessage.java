package com.ee06.wooms.domain.sketch.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single drawing action on the shared pixel canvas.
 * tool: "PEN" (paint colorIndex at x,y) or "ERASE" (clear that cell).
 * Note: "RESET" is broadcast by the server's archive flow to blank everyone's
 * canvas; a global "CLEAR" from clients is intentionally ignored (see SketchSession#apply)
 * so one user can't wipe the cumulative group mural.
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class StrokeMessage {
    private String nickname;
    private String tool;
    private Integer x;
    private Integer y;
    private Integer colorIndex;
}
