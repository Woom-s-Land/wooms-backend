package com.ee06.wooms.global.config;

import com.ee06.wooms.domain.enrollments.entity.Enrollment;
import com.ee06.wooms.domain.enrollments.entity.EnrollmentStatus;
import com.ee06.wooms.domain.enrollments.repository.EnrollmentRepository;
import com.ee06.wooms.domain.users.entity.User;
import com.ee06.wooms.domain.users.entity.UserStatus;
import com.ee06.wooms.domain.users.repository.UserRepository;
import com.ee06.wooms.domain.wooms.entity.MapColorStatus;
import com.ee06.wooms.domain.wooms.entity.Wooms;
import com.ee06.wooms.domain.wooms.repository.WoomsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Seeds local test data on startup. Dev profile only — never runs in prod.
 *
 * Accounts (login id = email):
 *   admin@wooms.com / 1234  (group leader)
 *   admin2..5@wooms.com / 1234  (members)
 *
 * Also seeds one default group "테스트 움즈" with all five accounts enrolled (ACCEPT),
 * so multiplayer testing works immediately after every restart (dev DB is in-memory).
 */
@Slf4j
@Profile("dev")
@Component
@RequiredArgsConstructor
public class DevDataInitializer implements CommandLineRunner {

    private record DevAccount(String email, String password, String nickname, int costume) {}

    private static final List<DevAccount> ACCOUNTS = List.of(
            new DevAccount("admin@wooms.com", "1234", "admin", 1),
            new DevAccount("admin2@wooms.com", "1234", "admin2", 2),
            new DevAccount("admin3@wooms.com", "1234", "admin3", 3),
            new DevAccount("admin4@wooms.com", "1234", "admin4", 4),
            new DevAccount("admin5@wooms.com", "1234", "admin5", 5)
    );

    private static final String GROUP_TITLE = "테스트 움즈";
    private static final UUID DEFAULT_INVITE_CODE =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final UserRepository userRepository;
    private final WoomsRepository woomsRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        Map<String, User> usersByEmail = new LinkedHashMap<>();

        for (DevAccount acc : ACCOUNTS) {
            User user = userRepository.findByEmail(acc.email()).orElseGet(() -> {
                User created = userRepository.save(User.builder()
                        .email(acc.email())
                        .password(passwordEncoder.encode(acc.password()))
                        .name(acc.nickname())
                        .nickname(acc.nickname())
                        .status(UserStatus.ACTIVE)
                        .costume(acc.costume())
                        .build());
                log.info("[dev-init] created account: {} / {}", acc.email(), acc.password());
                return created;
            });
            usersByEmail.put(acc.email(), user);
        }

        seedDefaultGroup(usersByEmail);
    }

    private void seedDefaultGroup(Map<String, User> usersByEmail) {
        if (woomsRepository.count() > 0) {
            log.info("[dev-init] a group already exists, skipping group seeding");
            return;
        }

        User leader = usersByEmail.get("admin@wooms.com");
        Wooms wooms = woomsRepository.save(Wooms.builder()
                .user(leader)
                .title(GROUP_TITLE)
                .inviteCode(DEFAULT_INVITE_CODE)
                .mapColorStatus(MapColorStatus.RED)
                .build());

        usersByEmail.values().forEach(user ->
                enrollmentRepository.save(Enrollment.of(user, wooms, EnrollmentStatus.ACCEPT)));

        log.info("[dev-init] seeded group '{}' (id={}, invite={}) with {} members",
                GROUP_TITLE, wooms.getId(), DEFAULT_INVITE_CODE, usersByEmail.size());
    }
}
