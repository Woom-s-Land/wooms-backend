package com.ee06.wooms.domain.sketchmafia.engine;

import com.ee06.wooms.domain.sketchmafia.entity.MafiaGame;
import com.ee06.wooms.domain.sketchmafia.entity.MafiaPlayer;
import com.ee06.wooms.domain.sketchmafia.dto.outbound.*;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single outbound gate for the mafia game. It builds ONLY public DTOs for
 * broadcasts (which structurally lack role/word), and sends secret role info
 * exclusively via convertAndSendToUser. Keeping all sends here makes the
 * "no secret leaks before RESULT" rule reviewable in one file.
 */
@Component
@RequiredArgsConstructor
public class MafiaBroadcaster {

    private final SimpMessagingTemplate template;

    private String topic(MafiaGame game, String suffix) {
        return "/ws/wooms/sketchmafia/" + suffix + "/" + game.getRoomId();
    }

    // ---- public broadcasts (no role/word) --------------------------------

    public void state(MafiaGame game) {
        state(game, null);
    }

    public void state(MafiaGame game, String message) {
        template.convertAndSend(topic(game, "state"), buildState(game, message));
    }

    public void reveal(MafiaGame game) {
        List<RevealBroadcast.Entry> drawings = new ArrayList<>();
        for (MafiaPlayer p : game.getPlayers().values()) {
            if (p.getRole() == null) continue; // round participants only
            drawings.add(RevealBroadcast.Entry.builder()
                    .nickname(p.getNickname())
                    .pixels(p.getGrid())
                    .submitted(p.isSubmitted())
                    .build());
        }
        template.convertAndSend(topic(game, "reveal"),
                RevealBroadcast.builder().size(MafiaGame.GRID_SIZE).drawings(drawings).build());
    }

    public void result(MafiaGame game) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (String target : game.getVotes().values()) {
            tally.merge(target, 1, Integer::sum);
        }
        List<ResultBroadcast.Role> roles = new ArrayList<>();
        for (MafiaPlayer p : game.getPlayers().values()) {
            if (p.getRole() == null) continue;
            roles.add(ResultBroadcast.Role.builder()
                    .nickname(p.getNickname())
                    .role(p.getRole().name())
                    .word(p.getWord())
                    .build());
        }
        template.convertAndSend(topic(game, "result"), ResultBroadcast.builder()
                .topic(game.getTopicPair() != null ? game.getTopicPair().topic() : null)
                .decoy(game.getTopicPair() != null ? game.getTopicPair().decoy() : null)
                .category(game.getTopicPair() != null ? game.getTopicPair().category() : null)
                .mafiaNickname(game.getMafiaNickname())
                .accusedNickname(game.getAccusedNickname())
                .tie(game.isTie())
                .outcome(game.getOutcome() != null ? game.getOutcome().name() : null)
                .voteTally(tally)
                .votes(new LinkedHashMap<>(game.getVotes()))
                .scores(new LinkedHashMap<>(game.getScores()))
                .roles(roles)
                .build());
    }

    public void chat(MafiaGame game, String nickname, String message) {
        template.convertAndSend(topic(game, "chat"),
                ChatBroadcast.builder().nickname(nickname).message(message).build());
    }

    // ---- private (per-user) sends ----------------------------------------

    public void role(MafiaPlayer player, MafiaGame game) {
        boolean mafia = player.isMafia();
        RoleAssignment payload = RoleAssignment.builder()
                .role(player.getRole().name())
                .word(player.getWord())
                .category(game.getTopicPair() != null ? game.getTopicPair().category() : null)
                .mafia(mafia)
                .note(mafia
                        ? "당신은 그림 마피아! 이 참고 단어로 자연스럽게 묻어가세요."
                        : "주제를 그려서 마피아를 가려내세요.")
                .build();
        sendToSession(player.getSessionId(), "/queue/sketchmafia/role", payload);
    }

    public void error(String sessionId, String message) {
        sendToSession(sessionId, "/queue/sketchmafia/error",
                ErrorMessage.builder().message(message).build());
    }

    /**
     * Deliver a private message to one specific STOMP session. Putting the sessionId
     * in the headers targets that exact session directly, which is more reliable than
     * resolving a user name through the user registry (the principal here is set by a
     * channel interceptor, so registry-based name resolution can miss).
     */
    private void sendToSession(String sessionId, String destination, Object payload) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        MessageHeaders headers = accessor.getMessageHeaders();
        template.convertAndSendToUser(sessionId, destination, payload, headers);
    }

    // ---- builders --------------------------------------------------------

    private GameStateBroadcast buildState(MafiaGame game, String message) {
        List<PlayerView> views = new ArrayList<>();
        for (MafiaPlayer p : game.getPlayers().values()) {
            views.add(PlayerView.builder()
                    .nickname(p.getNickname())
                    .costume(p.getCostume())
                    .connected(p.isConnected())
                    .host(p.getNickname().equals(game.getHostNickname()))
                    .submitted(p.isSubmitted())
                    .voted(p.getVotedFor() != null)
                    .score(game.getScores().getOrDefault(p.getNickname(), 0))
                    .build());
        }
        long endsAt = game.getPhaseEndsAtEpochMs();
        return GameStateBroadcast.builder()
                .phase(game.getPhase().name())
                .round(game.getRound())
                .players(views)
                .host(game.getHostNickname())
                .minPlayers(MafiaGame.MIN_PLAYERS)
                .maxPlayers(MafiaGame.MAX_PLAYERS)
                .endsAt(endsAt > 0 ? endsAt : null)
                .serverNow(System.currentTimeMillis())
                .message(message)
                .build();
    }
}
