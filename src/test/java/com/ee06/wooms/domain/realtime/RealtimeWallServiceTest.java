package com.ee06.wooms.domain.realtime;

import com.ee06.wooms.domain.sketch.entity.SketchMural;
import com.ee06.wooms.domain.sketch.entity.SketchWall;
import com.ee06.wooms.domain.sketch.repository.SketchMuralRepository;
import com.ee06.wooms.domain.sketch.repository.SketchWallRepository;
import com.ee06.wooms.domain.users.dto.CustomUserDetails;
import com.ee06.wooms.domain.users.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RealtimeWallServiceTest {
    private final RealtimeSessionService sessions = mock(RealtimeSessionService.class);
    private final SketchWallRepository walls = mock(SketchWallRepository.class);
    private final SketchMuralRepository murals = mock(SketchMuralRepository.class);
    private RealtimeWallService service;
    private final String roomId = UUID.randomUUID().toString();
    private final CustomUserDetails principal = new CustomUserDetails(User.builder().uuid(UUID.randomUUID()).build(), Map.of());
    private int[][] pixels;

    @BeforeEach void setUp() {
        service = new RealtimeWallService(sessions, walls, murals);
        when(sessions.session(principal, roomId)).thenReturn(new RealtimeSessionService.SessionInfo(roomId, principal.getUuid(), "verified-nickname", 3));
        pixels = blank(); pixels[3][4] = 7;
    }

    @Test void savesValidPixelsAsCsv() {
        when(walls.findByRoomId(roomId)).thenReturn(Optional.empty());
        var result = service.put(principal, roomId, new RealtimeWallService.WallRequest(pixels));
        assertEquals(32, result.getSize());
        assertEquals(7, result.getPixels()[3][4]);
        verify(walls).save(argThat(w -> w.getRoomId().equals(roomId) && csvValue(w.getPixels(), 3, 4) == 7));
    }

    @Test void rejectsWrongShapeWith400() {
        assertStatus(400, () -> service.put(principal, roomId, new RealtimeWallService.WallRequest(new int[31][32])));
    }

    @Test void rejectsOutOfPaletteValueWith400() {
        int[][] invalid = blank(); invalid[0][0] = 12;
        assertStatus(400, () -> service.put(principal, roomId, new RealtimeWallService.WallRequest(invalid)));
    }

    @Test void rejectsEmptyArchiveWith400() {
        assertStatus(400, () -> service.archive(principal, roomId, new RealtimeWallService.WallRequest(blank())));
        verifyNoInteractions(murals);
    }

    @Test void archivesWithVerifiedNicknameAndResetsWall() {
        when(murals.save(any(SketchMural.class))).thenAnswer(invocation -> {
            SketchMural m = invocation.getArgument(0);
            return SketchMural.builder().id(9L).roomId(m.getRoomId()).savedBy(m.getSavedBy()).pixels(m.getPixels()).build();
        });
        when(walls.findByRoomId(roomId)).thenReturn(Optional.of(SketchWall.builder().roomId(roomId).pixels("").build()));

        var result = service.archive(principal, roomId, new RealtimeWallService.WallRequest(pixels));

        assertEquals("verified-nickname", result.getSavedBy());
        assertEquals(7, result.getPixels()[3][4]);
        verify(murals).save(argThat(m -> m.getSavedBy().equals("verified-nickname") && csvValue(m.getPixels(), 3, 4) == 7));
        verify(walls).save(argThat(w -> Arrays.stream(w.getPixels().split(",")).allMatch("-1"::equals)));
    }

    private static int csvValue(String csv, int row, int col) { return Integer.parseInt(csv.split(",")[row * 32 + col]); }
    private static int[][] blank() { int[][] a = new int[32][32]; for (int[] row : a) Arrays.fill(row, -1); return a; }
    private static void assertStatus(int status, org.junit.jupiter.api.function.Executable action) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, action);
        assertEquals(status, exception.getStatusCode().value());
    }
}
