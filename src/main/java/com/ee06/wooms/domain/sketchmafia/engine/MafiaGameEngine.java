package com.ee06.wooms.domain.sketchmafia.engine;

import com.ee06.wooms.domain.sketchmafia.entity.GameOutcome;
import com.ee06.wooms.domain.sketchmafia.entity.GamePhase;
import com.ee06.wooms.domain.sketchmafia.entity.MafiaGame;
import com.ee06.wooms.domain.sketchmafia.entity.MafiaPlayer;
import com.ee06.wooms.domain.sketchmafia.entity.PlayerRole;
import com.ee06.wooms.domain.sketchmafia.common.MafiaTimings;
import com.ee06.wooms.domain.sketchmafia.repository.MafiaGameRepository;
import com.ee06.wooms.domain.sketchmafia.topic.TopicPair;
import com.ee06.wooms.domain.sketchmafia.topic.TopicService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static com.ee06.wooms.domain.sketchmafia.entity.GamePhase.*;

/**
 * Server-authoritative Drawing-Mafia engine: roster, role assignment, phase
 * state machine, timers and vote tally. Every public entry point and timer
 * callback runs under the per-game {@link MafiaGame#getLock() lock}; transitions
 * are guarded by the expected phase so "all submitted" and "timer fired" can
 * race safely (only the first wins, the rest are no-ops).
 *
 * Secrecy: role/word are only ever sent via {@link MafiaBroadcaster#role}; no
 * broadcast carries them before RESULT.
 */
@Slf4j
@Service
public class MafiaGameEngine {

    private static final int GRID = MafiaGame.GRID_SIZE;
    private static final int MAX_PALETTE_INDEX = 11; // 12 colors (0..11), -1 = empty

    private final MafiaGameRepository repository;
    private final MafiaBroadcaster broadcaster;
    private final TopicService topicService;
    private final MafiaTimings timings;
    private final ThreadPoolTaskScheduler scheduler;
    private final SecureRandom random = new SecureRandom();

    public MafiaGameEngine(MafiaGameRepository repository,
                           MafiaBroadcaster broadcaster,
                           TopicService topicService,
                           MafiaTimings timings,
                           @Qualifier("mafiaTaskScheduler") ThreadPoolTaskScheduler scheduler) {
        this.repository = repository;
        this.broadcaster = broadcaster;
        this.topicService = topicService;
        this.timings = timings;
        this.scheduler = scheduler;
    }

    // ===================== inbound entry points =====================

    public void join(String roomId, String sessionId, String nickname, Integer costume) {
        if (nickname == null || nickname.isBlank()) return;
        MafiaGame game = repository.getOrCreate(roomId);
        repository.bindSession(sessionId, roomId);
        game.getLock().lock();
        try {
            MafiaPlayer existing = game.getPlayers().get(nickname);
            if (existing != null) {
                if (existing.isConnected() && !sessionId.equals(existing.getSessionId())) {
                    // someone else is already live under this nickname — block hijack
                    broadcaster.error(sessionId, "이미 사용 중인 닉네임이에요.");
                    return;
                }
                existing.setSessionId(sessionId);
                existing.setConnected(true);
                if (existing.getRole() != null) broadcaster.role(existing, game); // reconnect mid-round
            } else {
                if (game.getPhase() == LOBBY && game.getPlayers().size() >= MafiaGame.MAX_PLAYERS) {
                    broadcaster.error(sessionId, "방이 가득 찼어요 (최대 " + MafiaGame.MAX_PLAYERS + "명).");
                    return;
                }
                MafiaPlayer p = new MafiaPlayer(nickname, costume, sessionId);
                game.getPlayers().put(nickname, p);
                if (game.getHostNickname() == null) game.setHostNickname(nickname);
                // joining mid-round => role stays null => spectator until next round
            }
            game.getScores().putIfAbsent(nickname, 0);
            broadcaster.state(game);
        } finally {
            game.getLock().unlock();
        }
    }

    public void start(String roomId, String sessionId, Integer drawSecondsOverride) {
        MafiaGame game = repository.get(roomId);
        if (game == null) return;
        game.getLock().lock();
        try {
            MafiaPlayer requester = findBySession(game, sessionId);
            if (requester == null) { broadcaster.error(sessionId, "게임에 먼저 참여해 주세요."); return; }
            if (!requester.getNickname().equals(game.getHostNickname())) {
                broadcaster.error(sessionId, "방장만 시작할 수 있어요."); return;
            }
            if (game.getPhase() != LOBBY && game.getPhase() != RESULT) {
                broadcaster.error(sessionId, "이미 게임이 진행 중이에요."); return;
            }

            game.getPlayers().values().removeIf(p -> !p.isConnected()); // clean roster
            List<MafiaPlayer> participants = new ArrayList<>(game.getPlayers().values());
            if (participants.size() < MafiaGame.MIN_PLAYERS) {
                broadcaster.error(sessionId, "최소 " + MafiaGame.MIN_PLAYERS + "명이 필요해요."); return;
            }
            if (participants.size() > MafiaGame.MAX_PLAYERS) {
                broadcaster.error(sessionId, "최대 " + MafiaGame.MAX_PLAYERS + "명까지 가능해요."); return;
            }

            // reset round state
            game.setRound(game.getRound() + 1);
            game.getVotes().clear();
            game.setOutcome(null);
            game.setAccusedNickname(null);
            game.setTie(false);
            participants.forEach(MafiaPlayer::resetForNewRound);

            // topic + mafia
            TopicPair pair = topicService.pick(game.getTopicBag());
            game.setTopicPair(pair);
            game.setDrawSeconds(clampDrawSeconds(drawSecondsOverride));
            MafiaPlayer mafia = participants.get(random.nextInt(participants.size()));
            game.setMafiaNickname(mafia.getNickname());
            for (MafiaPlayer p : participants) {
                if (p == mafia) { p.setRole(PlayerRole.MAFIA); p.setWord(pair.decoy()); }
                else { p.setRole(PlayerRole.CITIZEN); p.setWord(pair.topic()); }
            }

            // ASSIGN: send secret roles privately, then auto-advance to DRAW
            game.setPhase(ASSIGN);
            arm(game, timings.getAssignSeconds(), () -> enterDraw(roomId));
            participants.forEach(p -> broadcaster.role(p, game));
            broadcaster.state(game);
            log.info("[sketchmafia] room {} started round {} ({} players)", roomId, game.getRound(), participants.size());
        } finally {
            game.getLock().unlock();
        }
    }

    public void submit(String roomId, String sessionId, int[][] pixels) {
        MafiaGame game = repository.get(roomId);
        if (game == null) return;
        game.getLock().lock();
        try {
            if (game.getPhase() != DRAW) return;
            MafiaPlayer p = findBySession(game, sessionId);
            if (p == null || p.getRole() == null) return; // not a participant
            p.setGrid(normalizeGrid(pixels));
            p.setSubmitted(true);
            broadcaster.state(game);
            if (allParticipantsSubmitted(game)) enterReveal(roomId);
        } finally {
            game.getLock().unlock();
        }
    }

    public void vote(String roomId, String sessionId, String target) {
        MafiaGame game = repository.get(roomId);
        if (game == null) return;
        game.getLock().lock();
        try {
            if (game.getPhase() != VOTE) return;
            MafiaPlayer voter = findBySession(game, sessionId);
            if (voter == null || voter.getRole() == null || !voter.isConnected()) return;
            if (voter.getVotedFor() != null) return; // no double vote
            MafiaPlayer t = target == null ? null : game.getPlayers().get(target);
            if (t == null || t.getRole() == null || !t.isConnected()) {
                broadcaster.error(sessionId, "투표 대상이 올바르지 않아요."); return;
            }
            if (t == voter) { broadcaster.error(sessionId, "자신에게는 투표할 수 없어요."); return; }
            voter.setVotedFor(target);
            game.getVotes().put(voter.getNickname(), target);
            broadcaster.state(game);
            if (allParticipantsVoted(game)) enterResult(roomId);
        } finally {
            game.getLock().unlock();
        }
    }

    public void chat(String roomId, String sessionId, String message) {
        if (message == null || message.isBlank()) return;
        MafiaGame game = repository.get(roomId);
        if (game == null) return;
        game.getLock().lock();
        try {
            if (game.getPhase() != DISCUSS) return; // chat gated to discussion
            MafiaPlayer p = findBySession(game, sessionId);
            if (p == null) return;
            broadcaster.chat(game, p.getNickname(), message.length() > 200 ? message.substring(0, 200) : message);
        } finally {
            game.getLock().unlock();
        }
    }

    public void disconnect(String sessionId) {
        String roomId = repository.roomOfSession(sessionId);
        repository.unbindSession(sessionId);
        if (roomId == null) return;
        MafiaGame game = repository.get(roomId);
        if (game == null) return;
        game.getLock().lock();
        try {
            MafiaPlayer p = findBySession(game, sessionId);
            if (p == null) return;
            p.setConnected(false);
            boolean wasHost = p.getNickname().equals(game.getHostNickname());
            GamePhase ph = game.getPhase();

            if (ph == LOBBY || ph == RESULT) {
                game.getPlayers().remove(p.getNickname());
                if (wasHost) reassignHost(game);
                if (game.getPlayers().isEmpty()) { cancelTimer(game); repository.remove(roomId); return; }
                broadcaster.state(game);
                return;
            }

            // active round
            if (p.isMafia()) {
                if (ph == MAFIA_GUESS) {
                    finalize(game, GameOutcome.CITIZEN_WIN); // already-caught mafia bailed → citizens win
                } else {
                    abortToLobby(game, "마피아가 나가 게임이 중단됐어요.");
                }
                return;
            }
            if (wasHost) reassignHost(game);
            if (participants(game).size() < MafiaGame.MIN_PLAYERS) {
                abortToLobby(game, "인원이 부족해 게임이 중단됐어요.");
            } else if (ph == DRAW && allParticipantsSubmitted(game)) {
                enterReveal(roomId);
            } else if (ph == VOTE && allParticipantsVoted(game)) {
                enterResult(roomId);
            } else {
                broadcaster.state(game);
            }
        } finally {
            game.getLock().unlock();
        }
    }

    // ===================== timed transitions =====================

    void enterDraw(String roomId) {
        MafiaGame game = repository.get(roomId);
        if (game == null) return;
        game.getLock().lock();
        try {
            if (game.getPhase() != ASSIGN) return;
            game.setPhase(DRAW);
            arm(game, game.getDrawSeconds(), () -> enterReveal(roomId));
            broadcaster.state(game);
        } finally {
            game.getLock().unlock();
        }
    }

    void enterReveal(String roomId) {
        MafiaGame game = repository.get(roomId);
        if (game == null) return;
        game.getLock().lock();
        try {
            if (game.getPhase() != DRAW) return;
            for (MafiaPlayer p : game.getPlayers().values()) {
                if (p.getRole() != null && p.getGrid() == null) p.setGrid(emptyGrid());
            }
            game.setPhase(REVEAL);
            arm(game, timings.getRevealSeconds(), () -> enterDiscuss(roomId));
            broadcaster.reveal(game);
            broadcaster.state(game);
        } finally {
            game.getLock().unlock();
        }
    }

    void enterDiscuss(String roomId) {
        MafiaGame game = repository.get(roomId);
        if (game == null) return;
        game.getLock().lock();
        try {
            if (game.getPhase() != REVEAL) return;
            game.setPhase(DISCUSS);
            arm(game, timings.getDiscussSeconds(), () -> enterVote(roomId));
            broadcaster.state(game);
        } finally {
            game.getLock().unlock();
        }
    }

    void enterVote(String roomId) {
        MafiaGame game = repository.get(roomId);
        if (game == null) return;
        game.getLock().lock();
        try {
            if (game.getPhase() != DISCUSS) return;
            game.getVotes().clear();
            participants(game).forEach(p -> p.setVotedFor(null));
            game.setPhase(VOTE);
            arm(game, timings.getVoteSeconds(), () -> enterResult(roomId));
            broadcaster.state(game);
        } finally {
            game.getLock().unlock();
        }
    }

    void enterResult(String roomId) {
        MafiaGame game = repository.get(roomId);
        if (game == null) return;
        game.getLock().lock();
        try {
            if (game.getPhase() != VOTE) return;
            cancelTimer(game);

            // tally
            Map<String, Long> counts = new java.util.LinkedHashMap<>();
            for (String target : game.getVotes().values()) counts.merge(target, 1L, Long::sum);
            String accused = null;
            long top = -1;
            boolean tie = false;
            for (Map.Entry<String, Long> e : counts.entrySet()) {
                if (e.getValue() > top) { top = e.getValue(); accused = e.getKey(); tie = false; }
                else if (e.getValue() == top) { tie = true; }
            }
            if (counts.isEmpty()) { accused = null; tie = true; }

            game.setAccusedNickname(tie ? null : accused);
            game.setTie(tie);
            boolean caught = !tie && accused != null && accused.equals(game.getMafiaNickname());

            if (caught) {
                // Fake-Artist twist: the caught mafia gets one chance to guess the topic.
                game.setPhase(MAFIA_GUESS);
                arm(game, timings.getGuessSeconds(), () -> guessTimeout(roomId));
                broadcaster.state(game);
            } else {
                finalize(game, GameOutcome.MAFIA_WIN);
            }
        } finally {
            game.getLock().unlock();
        }
    }

    public void guess(String roomId, String sessionId, String word) {
        MafiaGame game = repository.get(roomId);
        if (game == null) return;
        game.getLock().lock();
        try {
            if (game.getPhase() != MAFIA_GUESS) return;
            MafiaPlayer p = findBySession(game, sessionId);
            if (p == null || !p.isMafia()) return; // only the caught mafia may guess
            boolean correct = game.getTopicPair() != null
                    && normalize(word).equals(normalize(game.getTopicPair().topic()));
            finalize(game, correct ? GameOutcome.MAFIA_STEAL_WIN : GameOutcome.CITIZEN_WIN);
        } finally {
            game.getLock().unlock();
        }
    }

    void guessTimeout(String roomId) {
        MafiaGame game = repository.get(roomId);
        if (game == null) return;
        game.getLock().lock();
        try {
            if (game.getPhase() != MAFIA_GUESS) return;
            finalize(game, GameOutcome.CITIZEN_WIN); // mafia didn't guess in time
        } finally {
            game.getLock().unlock();
        }
    }

    /** Finalize to RESULT. Assumes the game lock is held. */
    private void finalize(MafiaGame game, GameOutcome outcome) {
        cancelTimer(game);
        game.setOutcome(outcome);
        applyScores(game, outcome);
        game.setPhase(RESULT);
        game.setPhaseEndsAtEpochMs(0);
        broadcaster.result(game);
        broadcaster.state(game);
        log.info("[sketchmafia] room {} result: {} (mafia={}, accused={}, tie={})",
                game.getRoomId(), outcome, game.getMafiaNickname(), game.getAccusedNickname(), game.isTie());
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", "").toLowerCase();
    }

    /**
     * Award cumulative points for the round's outcome:
     *  - CITIZEN_WIN : each citizen +1, and citizens who voted the mafia +1 bonus.
     *  - MAFIA_WIN   : mafia +2.   - MAFIA_STEAL_WIN : mafia +3.   - ABORTED : none.
     */
    private void applyScores(MafiaGame game, GameOutcome outcome) {
        Map<String, Integer> scores = game.getScores();
        String mafia = game.getMafiaNickname();
        for (MafiaPlayer p : game.getPlayers().values()) {
            if (p.getRole() != null) scores.putIfAbsent(p.getNickname(), 0);
        }
        switch (outcome) {
            case CITIZEN_WIN -> {
                for (MafiaPlayer p : game.getPlayers().values()) {
                    if (p.getRole() == null || p.isMafia()) continue;
                    int delta = 1;
                    if (mafia != null && mafia.equals(p.getVotedFor())) delta += 1; // correct vote bonus
                    addScore(scores, p.getNickname(), delta);
                }
            }
            case MAFIA_WIN -> addScore(scores, mafia, 2);
            case MAFIA_STEAL_WIN -> addScore(scores, mafia, 3);
            default -> { /* ABORTED: no scoring */ }
        }
    }

    private void addScore(Map<String, Integer> scores, String nickname, int delta) {
        if (nickname == null) return;
        scores.merge(nickname, delta, Integer::sum);
    }

    // ===================== helpers =====================

    private void abortToLobby(MafiaGame game, String message) {
        cancelTimer(game);
        game.setPhase(LOBBY);
        game.setPhaseEndsAtEpochMs(0);
        game.setTopicPair(null);
        game.setMafiaNickname(null);
        game.setOutcome(GameOutcome.ABORTED);
        game.getVotes().clear();
        game.getPlayers().values().removeIf(pl -> !pl.isConnected());
        game.getPlayers().values().forEach(MafiaPlayer::resetForNewRound);
        if (game.getHostNickname() == null || game.getPlayers().get(game.getHostNickname()) == null) {
            reassignHost(game);
        }
        if (game.getPlayers().isEmpty()) { repository.remove(game.getRoomId()); return; }
        broadcaster.state(game, message);
    }

    private void reassignHost(MafiaGame game) {
        String chosen = null;
        for (MafiaPlayer pl : game.getPlayers().values()) {
            if (pl.isConnected()) { chosen = pl.getNickname(); break; }
        }
        if (chosen == null && !game.getPlayers().isEmpty()) {
            chosen = game.getPlayers().keySet().iterator().next();
        }
        game.setHostNickname(chosen);
    }

    private void arm(MafiaGame game, int seconds, Runnable callback) {
        cancelTimer(game);
        long endsAt = System.currentTimeMillis() + seconds * 1000L;
        game.setPhaseEndsAtEpochMs(endsAt);
        ScheduledFuture<?> future = scheduler.schedule(callback, Instant.ofEpochMilli(endsAt));
        game.setPhaseTimer(future);
    }

    private void cancelTimer(MafiaGame game) {
        ScheduledFuture<?> f = game.getPhaseTimer();
        if (f != null) { f.cancel(false); game.setPhaseTimer(null); }
    }

    private List<MafiaPlayer> participants(MafiaGame game) {
        List<MafiaPlayer> list = new ArrayList<>();
        for (MafiaPlayer p : game.getPlayers().values()) {
            if (p.getRole() != null && p.isConnected()) list.add(p);
        }
        return list;
    }

    private boolean allParticipantsSubmitted(MafiaGame game) {
        List<MafiaPlayer> ps = participants(game);
        return !ps.isEmpty() && ps.stream().allMatch(MafiaPlayer::isSubmitted);
    }

    private boolean allParticipantsVoted(MafiaGame game) {
        List<MafiaPlayer> ps = participants(game);
        return !ps.isEmpty() && ps.stream().allMatch(p -> p.getVotedFor() != null);
    }

    private MafiaPlayer findBySession(MafiaGame game, String sessionId) {
        if (sessionId == null) return null;
        for (MafiaPlayer p : game.getPlayers().values()) {
            if (sessionId.equals(p.getSessionId())) return p;
        }
        return null;
    }

    private int clampDrawSeconds(Integer override) {
        if (override == null) return timings.getDrawSeconds();
        return Math.max(1, Math.min(300, override));
    }

    private int[][] emptyGrid() {
        int[][] g = new int[GRID][GRID];
        for (int[] row : g) Arrays.fill(row, -1);
        return g;
    }

    private int[][] normalizeGrid(int[][] in) {
        int[][] g = emptyGrid();
        if (in == null) return g;
        for (int y = 0; y < GRID && y < in.length; y++) {
            int[] row = in[y];
            if (row == null) continue;
            for (int x = 0; x < GRID && x < row.length; x++) {
                int v = row[x];
                g[y][x] = (v >= 0 && v <= MAX_PALETTE_INDEX) ? v : -1;
            }
        }
        return g;
    }
}
