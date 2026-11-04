package com.ee06.wooms.domain.realtime.access.event;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonFormat;

/** Versioned, credential-free room access revocation contract. */
public record RoomAccessRevoked(
        int schemaVersion,
        UUID eventId,
        String type,
        UUID roomId,
        UUID userUuid,
        String reason,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String TYPE = "ROOM_ACCESS_REVOKED";
    public static final String MEMBER_LEFT = "MEMBER_LEFT";

    public static RoomAccessRevoked memberLeft(UUID roomId, UUID userUuid) {
        return new RoomAccessRevoked(CURRENT_SCHEMA_VERSION, UUID.randomUUID(), TYPE,
                roomId, userUuid, MEMBER_LEFT, Instant.now());
    }

    @JsonIgnore
    public boolean isValid() {
        return schemaVersion == CURRENT_SCHEMA_VERSION && eventId != null && TYPE.equals(type)
                && roomId != null && userUuid != null && occurredAt != null
                && reason != null && !reason.isBlank() && reason.length() <= 64;
    }
}
