package com.ee06.wooms.domain.sketch.common;

import com.ee06.wooms.domain.sketch.SketchSessionRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically persists changed murals to DB (debounced) and once more on shutdown.
 * @EnableScheduling is already active on WoomsApplication.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SketchFlushScheduler {

    private final SketchSessionRepository sketchSessionRepository;

    @Scheduled(fixedDelay = 10_000)
    public void flush() {
        try {
            sketchSessionRepository.flushDirty();
        } catch (Exception e) {
            log.warn("[sketch] mural flush failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void flushOnShutdown() {
        sketchSessionRepository.flushDirty();
    }
}
