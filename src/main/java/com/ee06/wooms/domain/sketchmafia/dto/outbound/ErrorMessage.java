package com.ee06.wooms.domain.sketchmafia.dto.outbound;

import lombok.Builder;
import lombok.Getter;

/** Private error to one player (/user/queue/sketchmafia/error). */
@Getter
@Builder
public class ErrorMessage {
    private String message;
}
