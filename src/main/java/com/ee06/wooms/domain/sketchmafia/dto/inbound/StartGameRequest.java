package com.ee06.wooms.domain.sketchmafia.dto.inbound;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class StartGameRequest {
    /** Optional override for the DRAW phase duration (seconds). Null = default. */
    private Integer drawSeconds;
}
