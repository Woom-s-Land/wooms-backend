package com.ee06.wooms.domain.sketchmafia.topic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Loads the topic/decoy dataset from resources at startup and hands out random
 * pairs. Reuses the shared {@link ObjectMapper} bean + {@link ClassPathResource}
 * (same pattern as DevDataInitializer).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TopicService {

    private static final String RESOURCE = "sketchmafia/topic-pairs.json";

    private final ObjectMapper objectMapper;

    private List<TopicPair> pairs = List.of(
            // fallback so the game never breaks if the file fails to load
            new TopicPair("동물", "고양이", "강아지"),
            new TopicPair("음식", "피자", "팬케이크")
    );

    @PostConstruct
    void load() {
        try {
            ClassPathResource resource = new ClassPathResource(RESOURCE);
            TopicData data = objectMapper.readValue(resource.getInputStream(), TopicData.class);
            if (data.pairs != null && !data.pairs.isEmpty()) {
                this.pairs = List.copyOf(data.pairs);
                log.info("[sketchmafia] loaded {} topic pairs from {}", pairs.size(), RESOURCE);
            } else {
                log.warn("[sketchmafia] {} had no pairs, using fallback", RESOURCE);
            }
        } catch (Exception e) {
            log.warn("[sketchmafia] failed to load {}: {} — using fallback pairs", RESOURCE, e.getMessage());
        }
    }

    /**
     * Pick a random pair, avoiding repeats within a game by draining a per-game
     * shuffled bag (reshuffled when empty).
     */
    public TopicPair pick(Bag bag) {
        if (bag.remaining.isEmpty()) {
            bag.refill(pairs);
        }
        int idx = ThreadLocalRandom.current().nextInt(bag.remaining.size());
        return bag.remaining.remove(idx);
    }

    /** Create a fresh non-repeating bag for one game. */
    public Bag newBag() {
        Bag bag = new Bag();
        bag.refill(pairs);
        return bag;
    }

    /** Per-game draw bag for non-repeating selection. */
    public static final class Bag {
        private final List<TopicPair> remaining = new ArrayList<>();

        void refill(List<TopicPair> all) {
            remaining.clear();
            remaining.addAll(all);
            Collections.shuffle(remaining);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TopicData(int version, List<TopicPair> pairs) {
    }
}
