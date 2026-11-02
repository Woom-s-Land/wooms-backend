package com.ee06.wooms.domain.sketchmafia.dto.outbound;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ChatBroadcast {
    private String nickname;
    private String message;
}
