package com.ee06.wooms.domain.realtime;

import com.ee06.wooms.domain.enrollments.entity.EnrollmentStatus;
import com.ee06.wooms.domain.enrollments.repository.EnrollmentRepository;
import com.ee06.wooms.domain.users.dto.CustomUserDetails;
import com.ee06.wooms.domain.users.entity.User;
import com.ee06.wooms.domain.users.repository.UserRepository;
import com.ee06.wooms.domain.wooms.entity.Wooms;
import com.ee06.wooms.domain.wooms.repository.WoomsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RealtimeSessionService {

    private final WoomsRepository woomsRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;

    public record SessionInfo(String roomId, String userUuid, String nickname, Integer costume) {}

    @Transactional(readOnly = true)
    public SessionInfo session(CustomUserDetails current, String roomId) {
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        UUID inviteCode;
        UUID userUuid;
        try {
            inviteCode = UUID.fromString(roomId);
            userUuid = UUID.fromString(current.getUuid());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid UUID", exception);
        }

        Wooms room = woomsRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        boolean accepted = enrollmentRepository.findByPkUserUuidAndWoomsId(userUuid, room.getId())
                .map(enrollment -> enrollment.getStatus() == EnrollmentStatus.ACCEPT)
                .orElse(false);
        if (!accepted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        User user = userRepository.findById(userUuid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return new SessionInfo(roomId, userUuid.toString(), user.getNickname(), user.getCostume());
    }
}
