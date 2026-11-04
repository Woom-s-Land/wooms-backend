package com.ee06.wooms.domain.realtime.access.redis;

import com.ee06.wooms.domain.realtime.access.event.RoomAccessRevoked;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "wooms.realtime.access", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisRoomAccessRevocationRelay {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final RealtimeAccessProperties properties;
    private final ThreadPoolTaskExecutor executor;

    public RedisRoomAccessRevocationRelay(StringRedisTemplate redis, ObjectMapper mapper,
                                          RealtimeAccessProperties properties,
                                          ThreadPoolTaskExecutor realtimeRevocationExecutor) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
        this.executor = realtimeRevocationExecutor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(RoomAccessRevoked event) {
        if (event == null || !event.isValid()) {
            log.warn("Discarded invalid room access revocation event");
            return;
        }
        try {
            executor.execute(() -> publish(event));
        } catch (RejectedExecutionException exception) {
            log.error("Room access revocation relay queue is full; eventId={} roomId={}",
                    event.eventId(), event.roomId());
        }
    }

    private void publish(RoomAccessRevoked event) {
        try {
            String payload = mapper.writeValueAsString(event);
            redis.convertAndSend(properties.getChannel(), payload);
        } catch (Exception exception) {
            // The database commit already succeeded. Do not turn a transport failure into a false HTTP failure.
            log.error("Unable to publish committed room access revocation; eventId={} roomId={} errorType={}",
                    event.eventId(), event.roomId(), exception.getClass().getSimpleName());
        }
    }
}
