package com.ee06.wooms.domain.realtime.access.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RoomAccessRevokedContractTest {
    @Test void serializedEventMatchesSharedVersionOneFixture() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        try (InputStream input = getClass().getResourceAsStream("/contracts/room-access-revoked-v1.json")) {
            assertNotNull(input);
            JsonNode fixture = mapper.readTree(input);
            RoomAccessRevoked event = new RoomAccessRevoked(1,
                    UUID.fromString(fixture.path("eventId").asText()), fixture.path("type").asText(),
                    UUID.fromString(fixture.path("roomId").asText()), UUID.fromString(fixture.path("userUuid").asText()),
                    fixture.path("reason").asText(), mapper.treeToValue(fixture.path("occurredAt"), java.time.Instant.class));
            assertTrue(event.isValid());
            assertEquals(fixture, mapper.readTree(mapper.writeValueAsBytes(event)));
            assertFalse(fixture.toString().matches("(?i).*(cookie|token|password|secret).*"));
        }
    }
}
