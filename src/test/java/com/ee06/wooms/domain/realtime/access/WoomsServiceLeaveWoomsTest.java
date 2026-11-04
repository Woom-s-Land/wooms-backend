package com.ee06.wooms.domain.realtime.access;

import com.ee06.wooms.domain.enrollments.entity.Enrollment;
import com.ee06.wooms.domain.enrollments.entity.EnrollmentStatus;
import com.ee06.wooms.domain.enrollments.repository.EnrollmentRepository;
import com.ee06.wooms.domain.users.dto.CustomUserDetails;
import com.ee06.wooms.domain.users.entity.User;
import com.ee06.wooms.domain.users.entity.UserStatus;
import com.ee06.wooms.domain.users.repository.UserRepository;
import com.ee06.wooms.domain.wooms.entity.Wooms;
import com.ee06.wooms.domain.realtime.access.event.RoomAccessRevoked;
import com.ee06.wooms.domain.wooms.exception.ex.WoomsLeaderNotLeftWoomsException;
import com.ee06.wooms.domain.wooms.repository.WoomsRepository;
import com.ee06.wooms.domain.realtime.access.port.RoomAccessRevocationPublisher;
import com.ee06.wooms.domain.wooms.service.WoomsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WoomsServiceLeaveWoomsTest {
    private static final UUID USER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ROOM_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final long WOOMS_ID = 7L;

    @Mock UserRepository users;
    @Mock WoomsRepository rooms;
    @Mock EnrollmentRepository enrollments;
    @Mock RoomAccessRevocationPublisher publisher;
    WoomsService service;
    User user;
    CustomUserDetails principal;
    Wooms room;

    @BeforeEach void setUp() {
        service = new WoomsService(users, rooms, enrollments, publisher);
        user = User.builder().uuid(USER_ID).email("member@example.invalid").name("Member")
                .nickname("member").status(UserStatus.ACTIVE).costume(1).build();
        principal = new CustomUserDetails(user, Map.of());
        room = Wooms.builder().id(WOOMS_ID).inviteCode(ROOM_ID).user(User.builder().uuid(UUID.randomUUID()).build()).build();
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(enrollments.existsByPkUserUuidAndPkWoomsId(USER_ID, WOOMS_ID)).thenReturn(true);
        when(rooms.existsUserUuidByUserUuidAndId(USER_ID, WOOMS_ID)).thenReturn(false);
    }

    @Test void acceptedMemberLeaveRequestsExactlyOneCredentialFreeEvent() {
        Enrollment enrollment = Enrollment.of(user, room, EnrollmentStatus.ACCEPT);
        when(enrollments.findByPkUserUuidAndWoomsId(USER_ID, WOOMS_ID)).thenReturn(Optional.of(enrollment));
        when(rooms.findWoomsById(WOOMS_ID)).thenReturn(Optional.of(room));

        service.leaveWooms(principal, WOOMS_ID);

        assertEquals(EnrollmentStatus.REFUSE, enrollment.getStatus());
        ArgumentCaptor<RoomAccessRevoked> event = ArgumentCaptor.forClass(RoomAccessRevoked.class);
        verify(publisher).publishAfterCommit(event.capture());
        assertTrue(event.getValue().isValid());
        assertEquals(ROOM_ID, event.getValue().roomId());
        assertEquals(USER_ID, event.getValue().userUuid());
        assertEquals(RoomAccessRevoked.MEMBER_LEFT, event.getValue().reason());
        verify(rooms).findWoomsById(WOOMS_ID);
    }

    @Test void waitingOrAlreadyRefusedMembershipDoesNotPublishRevocation() {
        for (EnrollmentStatus prior : new EnrollmentStatus[]{EnrollmentStatus.WAITING, EnrollmentStatus.REFUSE}) {
            reset(enrollments, rooms, publisher);
            when(users.findById(USER_ID)).thenReturn(Optional.of(user));
            when(enrollments.existsByPkUserUuidAndPkWoomsId(USER_ID, WOOMS_ID)).thenReturn(true);
            when(rooms.existsUserUuidByUserUuidAndId(USER_ID, WOOMS_ID)).thenReturn(false);
            Enrollment enrollment = Enrollment.of(user, room, prior);
            when(enrollments.findByPkUserUuidAndWoomsId(USER_ID, WOOMS_ID)).thenReturn(Optional.of(enrollment));

            service.leaveWooms(principal, WOOMS_ID);

            assertEquals(EnrollmentStatus.REFUSE, enrollment.getStatus());
            verify(publisher, never()).publishAfterCommit(any());
            verify(rooms, never()).findWoomsById(anyLong());
        }
    }

    @Test void deniedOrUnenrolledLeaveDoesNotPublish() {
        when(rooms.existsUserUuidByUserUuidAndId(USER_ID, WOOMS_ID)).thenReturn(true);
        when(enrollments.countByWoomsIdAndStatus(WOOMS_ID, EnrollmentStatus.ACCEPT)).thenReturn(2);
        assertThrows(WoomsLeaderNotLeftWoomsException.class, () -> service.leaveWooms(principal, WOOMS_ID));
        verify(publisher, never()).publishAfterCommit(any());

        reset(enrollments, rooms, publisher);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(enrollments.existsByPkUserUuidAndPkWoomsId(USER_ID, WOOMS_ID)).thenReturn(false);
        assertThrows(RuntimeException.class, () -> service.leaveWooms(principal, WOOMS_ID));
        verify(publisher, never()).publishAfterCommit(any());
    }
}
