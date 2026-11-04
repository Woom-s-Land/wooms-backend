package com.ee06.wooms.domain.realtime.access.port;

import com.ee06.wooms.domain.realtime.access.event.RoomAccessRevoked;

/** Domain-facing port; transport and transaction timing belong to adapters. */
public interface RoomAccessRevocationPublisher {
    void publishAfterCommit(RoomAccessRevoked event);
}
