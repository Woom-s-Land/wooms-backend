package com.ee06.wooms.benchmark;

import com.ee06.wooms.WoomsApplication;
import com.ee06.wooms.domain.enrollments.entity.Enrollment;
import com.ee06.wooms.domain.enrollments.entity.EnrollmentStatus;
import com.ee06.wooms.domain.enrollments.repository.EnrollmentRepository;
import com.ee06.wooms.domain.users.entity.User;
import com.ee06.wooms.domain.users.entity.UserStatus;
import com.ee06.wooms.domain.users.repository.UserRepository;
import com.ee06.wooms.domain.wooms.entity.MapColorStatus;
import com.ee06.wooms.domain.wooms.entity.Wooms;
import com.ee06.wooms.domain.wooms.repository.WoomsRepository;
import com.ee06.wooms.global.jwt.JWTUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Test-only backend process that seeds authenticated benchmark users in its own H2 memory database. */
public final class RealtimeFixtureServerMain {
    private static final int ROOM_COUNT = 100;
    private static final int USERS_PER_ROOM = 10;

    private RealtimeFixtureServerMain() {}

    public static void main(String[] args) throws Exception {
        ConfigurableApplicationContext context = SpringApplication.run(WoomsApplication.class, args);
        try {
            FixtureDocument fixture = seed(context);
            String output = System.getenv("REALTIME_FIXTURE_FILE");
            if (output == null || output.isBlank()) {
                throw new IllegalStateException("REALTIME_FIXTURE_FILE must point to a TEMP file");
            }
            Path fixtureFile = Path.of(output).toAbsolutePath().normalize();
            if (!fixtureFile.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("Fixture credentials must be written under TEMP");
            }
            Files.writeString(fixtureFile, new ObjectMapper().writeValueAsString(fixture),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            System.out.printf("REALTIME_FIXTURE_READY rooms=%d users=%d file=%s%n",
                    ROOM_COUNT, ROOM_COUNT * USERS_PER_ROOM, fixtureFile);
        } catch (Exception exception) {
            context.close();
            throw exception;
        }
    }

    private static FixtureDocument seed(ConfigurableApplicationContext context) {
        UserRepository users = context.getBean(UserRepository.class);
        WoomsRepository woomsRepository = context.getBean(WoomsRepository.class);
        EnrollmentRepository enrollments = context.getBean(EnrollmentRepository.class);
        JWTUtil jwtUtil = context.getBean(JWTUtil.class);

        List<User> allUsers = new ArrayList<>(ROOM_COUNT * USERS_PER_ROOM);
        for (int index = 0; index < ROOM_COUNT * USERS_PER_ROOM; index++) {
            String suffix = "%04d".formatted(index);
            allUsers.add(User.builder()
                    .email("realtime-bench-" + suffix + "@example.invalid")
                    .name("Realtime Bench " + suffix)
                    .nickname("rtb-" + suffix)
                    .status(UserStatus.ACTIVE)
                    .costume(index % 10 + 1)
                    .build());
        }
        allUsers = users.saveAll(allUsers);
        users.flush();

        List<Wooms> rooms = new ArrayList<>(ROOM_COUNT);
        for (int roomIndex = 0; roomIndex < ROOM_COUNT; roomIndex++) {
            rooms.add(Wooms.builder()
                    .user(allUsers.get(roomIndex * USERS_PER_ROOM))
                    .title("Realtime benchmark room %03d".formatted(roomIndex))
                    .inviteCode(UUID.randomUUID())
                    .mapColorStatus(MapColorStatus.RED)
                    .build());
        }
        rooms = woomsRepository.saveAll(rooms);
        woomsRepository.flush();

        List<Enrollment> memberships = new ArrayList<>(ROOM_COUNT * USERS_PER_ROOM);
        List<FixtureRoom> fixtureRooms = new ArrayList<>(ROOM_COUNT);
        for (int roomIndex = 0; roomIndex < ROOM_COUNT; roomIndex++) {
            Wooms room = rooms.get(roomIndex);
            List<FixtureUser> fixtureUsers = new ArrayList<>(USERS_PER_ROOM);
            for (int memberIndex = 0; memberIndex < USERS_PER_ROOM; memberIndex++) {
                int userIndex = roomIndex * USERS_PER_ROOM + memberIndex;
                User user = allUsers.get(userIndex);
                memberships.add(Enrollment.of(user, room, EnrollmentStatus.ACCEPT));
                String userUuid = user.getUuid().toString();
                String cookieValue = jwtUtil.generateAccessToken(userUuid, user.getNickname(),
                        user.getCostume().toString(), room.getInviteCode().toString());
                if (!jwtUtil.validateToken(cookieValue) || !userUuid.equals(jwtUtil.getUuid(cookieValue))
                        || !room.getInviteCode().toString().equals(jwtUtil.getChannelUuid(cookieValue))) {
                    throw new IllegalStateException("Generated benchmark JWT failed backend validation");
                }
                fixtureUsers.add(new FixtureUser(userUuid, user.getNickname(), user.getCostume(),
                        "Authorization=" + cookieValue));
            }
            fixtureRooms.add(new FixtureRoom(room.getInviteCode().toString(), fixtureUsers));
        }
        enrollments.saveAll(memberships);
        enrollments.flush();

        return new FixtureDocument(UUID.randomUUID().toString(), ROOM_COUNT,
                ROOM_COUNT * USERS_PER_ROOM, fixtureRooms);
    }

    private record FixtureDocument(String runId, int roomCount, int userCount, List<FixtureRoom> rooms) {}
    private record FixtureRoom(String roomId, List<FixtureUser> users) {}
    private record FixtureUser(String userUuid, String nickname, Integer costume, String cookie) {}
}
