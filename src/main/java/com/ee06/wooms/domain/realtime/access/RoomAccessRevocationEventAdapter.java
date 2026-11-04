package com.ee06.wooms.domain.realtime.access;

import com.ee06.wooms.domain.realtime.access.event.RoomAccessRevoked;
import com.ee06.wooms.domain.realtime.access.port.RoomAccessRevocationPublisher;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Objects;

/** Relays domain intent into Spring's transaction-aware event mechanism. */
public final class RoomAccessRevocationEventAdapter implements RoomAccessRevocationPublisher {
    private final ApplicationEventPublisher events;

    public RoomAccessRevocationEventAdapter(ApplicationEventPublisher events) {
        this.events = Objects.requireNonNull(events);
    }

    @Override
    public void publishAfterCommit(RoomAccessRevoked event) {
        events.publishEvent(Objects.requireNonNull(event));
    }
}
