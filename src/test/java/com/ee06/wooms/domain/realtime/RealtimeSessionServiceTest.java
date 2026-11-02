package com.ee06.wooms.domain.realtime;

import com.ee06.wooms.domain.enrollments.entity.Enrollment;
import com.ee06.wooms.domain.enrollments.entity.EnrollmentStatus;
import com.ee06.wooms.domain.enrollments.repository.EnrollmentRepository;
import com.ee06.wooms.domain.users.dto.CustomUserDetails;
import com.ee06.wooms.domain.users.entity.User;
import com.ee06.wooms.domain.users.repository.UserRepository;
import com.ee06.wooms.domain.wooms.entity.Wooms;
import com.ee06.wooms.domain.wooms.repository.WoomsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RealtimeSessionServiceTest {
    private final WoomsRepository wooms = mock(WoomsRepository.class);
    private final EnrollmentRepository enrollments = mock(EnrollmentRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private RealtimeSessionService service;
    private final UUID userUuid = UUID.randomUUID();
    private final UUID roomUuid = UUID.randomUUID();
    private final Wooms room = Wooms.builder().id(42L).inviteCode(roomUuid).build();
    private final CustomUserDetails principal = new CustomUserDetails(
            User.builder().uuid(userUuid).nickname("stale").costume(1).build(), Map.of());

    @BeforeEach void setUp() {
        service = new RealtimeSessionService(wooms, enrollments, users);
        when(wooms.findByInviteCode(roomUuid)).thenReturn(Optional.of(room));
    }

    @Test void returnsCurrentDatabaseIdentityForAcceptedEnrollment() {
        when(enrollments.findByPkUserUuidAndWoomsId(userUuid, 42L)).thenReturn(Optional.of(
                Enrollment.builder().status(EnrollmentStatus.ACCEPT).build()));
        when(users.findById(userUuid)).thenReturn(Optional.of(
                User.builder().uuid(userUuid).nickname("current-name").costume(7).build()));

        var result = service.session(principal, roomUuid.toString());

        assertEquals(new RealtimeSessionService.SessionInfo(roomUuid.toString(), userUuid.toString(), "current-name", 7), result);
        verify(users).findById(userUuid);
    }

    @Test void rejectsMissingPrincipalWith401() { assertStatus(401, () -> service.session(null, roomUuid.toString())); }
    @Test void rejectsMalformedRoomUuidWith400() { assertStatus(400, () -> service.session(principal, "bad-room")); }

    @Test void rejectsMissingRoomWith404() {
        when(wooms.findByInviteCode(roomUuid)).thenReturn(Optional.empty());
        assertStatus(404, () -> service.session(principal, roomUuid.toString()));
    }

    @Test void rejectsWaitingEnrollmentWith403() { enrollmentDenied(EnrollmentStatus.WAITING); }
    @Test void rejectsRefusedEnrollmentWith403() { enrollmentDenied(EnrollmentStatus.REFUSE); }

    @Test void rejectsMissingEnrollmentWith403() {
        when(enrollments.findByPkUserUuidAndWoomsId(userUuid, 42L)).thenReturn(Optional.empty());
        assertStatus(403, () -> service.session(principal, roomUuid.toString()));
    }

    private void enrollmentDenied(EnrollmentStatus status) {
        when(enrollments.findByPkUserUuidAndWoomsId(userUuid, 42L)).thenReturn(Optional.of(
                Enrollment.builder().status(status).build()));
        assertStatus(403, () -> service.session(principal, roomUuid.toString()));
    }

    private static void assertStatus(int status, org.junit.jupiter.api.function.Executable action) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, action);
        assertEquals(status, exception.getStatusCode().value());
    }
}
