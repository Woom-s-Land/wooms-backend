package com.ee06.wooms.domain.sketchmafia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.lang.NonNull;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Drawing-Mafia game over real STOMP: 3 headless clients play one full
 * round and we assert role secrecy, phase flow, the caught-mafia outcome, and scoring.
 * The WS endpoint accepts anonymous connections (nickname comes in the join payload),
 * so no login/JWT is needed. Phase timers are shortened via properties.
 *
 * Converter: inbound broadcasts are application/json (Jackson -> JsonNode); outbound
 * sends are plain JSON strings (StringMessageConverter), matching the controllers'
 * {@code String content} params. Order matters: String converter first for sends.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mafiaitest;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sketchmafia.assign-seconds=1",
        "sketchmafia.reveal-seconds=1",
        "sketchmafia.discuss-seconds=1",
        "sketchmafia.vote-seconds=3",
        "sketchmafia.guess-seconds=3",
})
class MafiaGameFlowTest {

    @LocalServerPort
    int port;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String ROOM = "itest-room";
    private static final long T = 12000; // generic poll timeout (ms)

    @Test
    void fullRound_citizensCatchMafia_thenScores() throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(512 * 1024); // reveal frames carry full grids
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient(container));
        stomp.setInboundMessageSizeLimit(512 * 1024);
        List<MessageConverter> converters = new ArrayList<>();
        converters.add(new StringMessageConverter());          // outbound String sends
        converters.add(new MappingJackson2MessageConverter()); // inbound JSON -> JsonNode
        stomp.setMessageConverter(new CompositeMessageConverter(converters));

        List<String> publicBroadcasts = new CopyOnWriteArrayList<>(); // state+reveal only

        Client a = connect(stomp, "A", publicBroadcasts);
        Client b = connect(stomp, "B", publicBroadcasts);
        Client c = connect(stomp, "C", publicBroadcasts);
        List<Client> all = List.of(a, b, c);

        a.send("join", Map.of("nickname", "A", "costume", 1));
        b.send("join", Map.of("nickname", "B", "costume", 2));
        c.send("join", Map.of("nickname", "C", "costume", 3));

        JsonNode lobby = pollState(a, "LOBBY-3players",
                n -> n.get("players").size() == 3 && "LOBBY".equals(n.get("phase").asText()));
        String host = lobby.get("host").asText();
        Client hostClient = all.stream().filter(x -> x.nickname.equals(host)).findFirst().orElseThrow();

        hostClient.send("start", Map.of("drawSeconds", 1));

        // confirm the game actually started (left LOBBY) before expecting roles
        pollState(a, "left-LOBBY", n -> !"LOBBY".equals(n.get("phase").asText()));

        JsonNode ra = take("role-A", a.roleQ), rb = take("role-B", b.roleQ), rc = take("role-C", c.roleQ);
        List<JsonNode> roles = List.of(ra, rb, rc);

        assertThat(roles.stream().filter(r -> r.get("mafia").asBoolean()).count())
                .as("정확히 1명만 마피아").isEqualTo(1);

        String mafia = null, topic = null, decoy = null;
        for (int i = 0; i < 3; i++) {
            JsonNode r = roles.get(i);
            if (r.get("mafia").asBoolean()) { mafia = all.get(i).nickname; decoy = r.get("word").asText(); }
            else { topic = r.get("word").asText(); }
        }
        assertThat(mafia).isNotNull();
        assertThat(topic).isNotBlank();
        assertThat(decoy).isNotBlank();
        assertThat(decoy).as("디코이는 주제와 달라야").isNotEqualTo(topic);
        final String mafiaNick = mafia;

        // DRAW: everyone submits -> triggers REVEAL
        for (Client x : all) x.send("submit", Map.of("pixels", grid()));

        JsonNode reveal = take("reveal", a.revealQ);
        assertThat(reveal.get("drawings").size()).isEqualTo(3);

        pollState(a, "VOTE", n -> "VOTE".equals(n.get("phase").asText()));

        // citizens vote the mafia; mafia votes a citizen -> mafia caught
        String aCitizen = all.stream().map(y -> y.nickname).filter(nn -> !nn.equals(mafiaNick)).findFirst().orElseThrow();
        for (Client x : all) {
            x.send("vote", Map.of("target", x.nickname.equals(mafiaNick) ? aCitizen : mafiaNick));
        }

        // caught -> MAFIA_GUESS; mafia guesses wrong -> CITIZEN_WIN
        pollState(a, "MAFIA_GUESS", n -> "MAFIA_GUESS".equals(n.get("phase").asText()));
        all.stream().filter(x -> x.nickname.equals(mafiaNick)).findFirst().orElseThrow()
                .send("guess", Map.of("word", "__definitely_wrong_guess__"));

        JsonNode result = take("result", a.resultQ);
        assertThat(result.get("outcome").asText()).isEqualTo("CITIZEN_WIN");
        assertThat(result.get("mafiaNickname").asText()).isEqualTo(mafiaNick);
        assertThat(result.get("topic").asText()).isEqualTo(topic);

        // scoring: each citizen who correctly voted mafia = +1 win +1 bonus = 2; mafia 0
        JsonNode scores = result.get("scores");
        for (Client x : all) {
            int sc = scores.get(x.nickname).asInt();
            assertThat(sc).as(x.nickname + " 점수").isEqualTo(x.nickname.equals(mafiaNick) ? 0 : 2);
        }

        // SECRECY: no pre-RESULT broadcast leaked the words or a role field
        for (String msg : publicBroadcasts) {
            assertThat(msg).as("주제 누설").doesNotContain(topic);
            assertThat(msg).as("디코이 누설").doesNotContain(decoy);
            assertThat(msg).as("role 필드 누설").doesNotContain("\"role\"");
            assertThat(msg).as("word 필드 누설").doesNotContain("\"word\"");
        }

        for (Client x : all) x.session.disconnect();
    }

    // ---------- helpers ----------

    private Client connect(WebSocketStompClient stomp, String nickname, List<String> corpus) throws Exception {
        WebSocketHttpHeaders hs = new WebSocketHttpHeaders();
        hs.add("Origin", "http://localhost:5173"); // satisfy allowedOrigins
        StompSession session = stomp.connectAsync(
                "ws://localhost:" + port + "/ws", hs, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

        Client c = new Client(nickname, session);
        sub(session, "/user/queue/sketchmafia/role", c.roleQ, null);
        sub(session, "/ws/wooms/sketchmafia/state/" + ROOM, c.stateQ, corpus);
        sub(session, "/ws/wooms/sketchmafia/reveal/" + ROOM, c.revealQ, corpus);
        sub(session, "/ws/wooms/sketchmafia/result/" + ROOM, c.resultQ, null); // excluded from secrecy corpus
        return c;
    }

    private void sub(StompSession s, String dest, BlockingQueue<JsonNode> q, List<String> corpus) {
        s.subscribe(dest, new StompFrameHandler() {
            @Override public Type getPayloadType(@NonNull StompHeaders headers) { return JsonNode.class; }
            @Override public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                JsonNode node = (JsonNode) payload;
                q.add(node);
                if (corpus != null) corpus.add(node.toString());
            }
        });
    }

    private JsonNode take(String label, BlockingQueue<JsonNode> q) throws Exception {
        JsonNode msg = q.poll(T, TimeUnit.MILLISECONDS);
        assertThat(msg).as("take[" + label + "] 타임아웃").isNotNull();
        return msg;
    }

    private JsonNode pollState(Client c, String label, Predicate<JsonNode> pred) throws Exception {
        long end = System.currentTimeMillis() + T;
        List<String> seen = new ArrayList<>();
        while (System.currentTimeMillis() < end) {
            JsonNode n = c.stateQ.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (n == null) break;
            seen.add(n.get("phase").asText());
            if (pred.test(n)) return n;
        }
        throw new AssertionError("pollState[" + label + "] 타임아웃 — 본 페이즈들=" + seen);
    }

    private int[][] grid() {
        int[][] g = new int[32][32];
        for (int[] row : g) java.util.Arrays.fill(row, -1);
        g[0][0] = 1; g[5][5] = 3; g[10][10] = 7;
        return g;
    }

    /** One headless STOMP player. */
    private final class Client {
        final String nickname;
        final StompSession session;
        final BlockingQueue<JsonNode> roleQ = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonNode> stateQ = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonNode> revealQ = new LinkedBlockingQueue<>();
        final BlockingQueue<JsonNode> resultQ = new LinkedBlockingQueue<>();

        Client(String nickname, StompSession session) {
            this.nickname = nickname;
            this.session = session;
        }

        void send(String suffix, Object body) throws Exception {
            session.send("/ws/send/sketchmafia/" + suffix + "/" + ROOM, mapper.writeValueAsString(body));
        }
    }
}
