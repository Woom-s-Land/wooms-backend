package com.ee06.wooms.domain.sketch;

import com.ee06.wooms.domain.sketch.entity.SketchSession;
import com.ee06.wooms.domain.sketch.entity.SketchWall;
import com.ee06.wooms.domain.sketch.repository.SketchWallRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory cache of live mural canvases (one {@link SketchSession} per roomId),
 * backed by {@link SketchWall} for durability. Mirrors {@code ChannelRepository}.
 *
 * - get(): lazily loads the persisted mural from DB on first access.
 * - markDirty()/flushDirty(): a debounced flush (driven by SketchFlushScheduler)
 *   writes changed canvases back to DB, avoiding a DB write per pixel.
 */
@Component
@RequiredArgsConstructor
public class SketchSessionRepository {

    private final SketchWallRepository wallRepository;

    private final ConcurrentMap<String, SketchSession> sessions = new ConcurrentHashMap<>();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    public SketchSession get(String roomId) {
        return sessions.computeIfAbsent(roomId, this::load);
    }

    private SketchSession load(String roomId) {
        SketchSession session = new SketchSession();
        wallRepository.findByRoomId(roomId)
                .ifPresent(wall -> session.deserialize(wall.getPixels()));
        return session;
    }

    public void markDirty(String roomId) {
        dirty.add(roomId);
    }

    /** Persist all changed canvases. Each room is its own save so one failure can't block the rest. */
    public void flushDirty() {
        for (String roomId : new ArrayList<>(dirty)) {
            dirty.remove(roomId); // claim first; a concurrent stroke re-marks for the next cycle
            SketchSession session = sessions.get(roomId);
            if (session == null) continue;

            String pixels = session.serialize();
            wallRepository.findByRoomId(roomId).ifPresentOrElse(
                    wall -> {
                        wall.updatePixels(pixels);
                        wallRepository.save(wall);
                    },
                    () -> wallRepository.save(
                            SketchWall.builder().roomId(roomId).pixels(pixels).build())
            );
        }
    }

    public void remove(String roomId) {
        sessions.remove(roomId);
    }

    public int size() {
        return sessions.size();
    }
}
