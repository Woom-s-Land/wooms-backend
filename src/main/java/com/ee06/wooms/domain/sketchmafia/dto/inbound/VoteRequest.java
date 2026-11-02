package com.ee06.wooms.domain.sketchmafia.dto.inbound;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class VoteRequest {
    /** nickname of the suspected mafia. */
    private String target;
}
