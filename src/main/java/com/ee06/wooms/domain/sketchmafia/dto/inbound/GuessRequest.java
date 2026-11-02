package com.ee06.wooms.domain.sketchmafia.dto.inbound;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The caught mafia's one-shot guess at the real topic word. */
@Getter
@Setter
@NoArgsConstructor
public class GuessRequest {
    private String word;
}
