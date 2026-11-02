package com.ee06.wooms.domain.sketchmafia.common;

import com.ee06.wooms.domain.sketchmafia.engine.MafiaGameEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Routes STOMP disconnects to the mafia engine so a leaving player updates the
 * roster / aborts the round. Independent of the chat domain's own disconnect
 * listener (both fire for the same session, each handling its own state).
 */
@Component
@RequiredArgsConstructor
public class MafiaDisconnectListener {

    private final MafiaGameEngine engine;

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        if (sessionId != null) engine.disconnect(sessionId);
    }
}
