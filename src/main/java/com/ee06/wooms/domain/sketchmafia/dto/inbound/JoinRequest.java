package com.ee06.wooms.domain.sketchmafia.dto.inbound;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class JoinRequest {
    private String nickname;
    private Integer costume;
}
