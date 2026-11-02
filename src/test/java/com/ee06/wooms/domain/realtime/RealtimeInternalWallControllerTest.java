package com.ee06.wooms.domain.realtime;

import com.ee06.wooms.domain.wooms.repository.WoomsRepository;
import com.ee06.wooms.domain.wooms.entity.Wooms;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RealtimeInternalWallControllerTest {
    private final RealtimeWallService wallService = mock(RealtimeWallService.class);
    private final WoomsRepository woomsRepository = mock(WoomsRepository.class);
    private final RealtimeInternalWallController controller = new RealtimeInternalWallController(wallService, woomsRepository);
    private final String roomId = UUID.randomUUID().toString();
    private final RealtimeWallService.WallRequest request = new RealtimeWallService.WallRequest(new int[32][32]);

    @BeforeEach void secret() { ReflectionTestUtils.setField(controller, "internalSecret", "unit-test-random-secret-value-32chars"); }

    @Test void rejectsWhenInternalSecretIsMissingOrTooShortWith503() {
        ReflectionTestUtils.setField(controller, "internalSecret", "");
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> controller.put(roomId, "x", request));
        ReflectionTestUtils.setField(controller, "internalSecret", "short");
        assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> controller.put(roomId, "x", request));
        verifyNoInteractions(woomsRepository, wallService);
    }

    @Test void rejectsMissingOrIncorrectKeyWith401() {
        assertStatus(HttpStatus.UNAUTHORIZED, () -> controller.put(roomId, null, request));
        assertStatus(HttpStatus.UNAUTHORIZED, () -> controller.put(roomId, "incorrect-key-value", request));
        verifyNoInteractions(woomsRepository, wallService);
    }

    @Test void rejectsMalformedUuidWith400() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> controller.put("not-a-uuid", "unit-test-random-secret-value-32chars", request));
    }

    @Test void rejectsUnknownRoomWith404() {
        when(woomsRepository.findByInviteCode(UUID.fromString(roomId))).thenReturn(Optional.empty());
        assertStatus(HttpStatus.NOT_FOUND, () -> controller.put(roomId, "unit-test-random-secret-value-32chars", request));
        verifyNoInteractions(wallService);
    }

    @Test void validKeyAndRoomDelegatesToSocketPersistence() {
        when(woomsRepository.findByInviteCode(UUID.fromString(roomId))).thenReturn(Optional.of(
                Wooms.builder().id(1L).inviteCode(UUID.fromString(roomId)).build()));
        controller.put(roomId, "unit-test-random-secret-value-32chars", request);
        verify(wallService).putFromSocket(roomId, request);
    }

    private static void assertStatus(HttpStatus status, org.junit.jupiter.api.function.Executable action) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, action);
        assertEquals(status, exception.getStatusCode());
    }
}
