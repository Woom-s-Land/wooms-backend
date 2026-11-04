package com.ee06.wooms.domain.realtime.access.redis;

import com.ee06.wooms.domain.realtime.access.RoomAccessRevocationEventAdapter;
import com.ee06.wooms.domain.realtime.access.event.RoomAccessRevoked;
import com.ee06.wooms.domain.realtime.access.port.RoomAccessRevocationPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoomAccessRevocationAfterCommitTest {
    @Test void relayRunsOnlyAfterCommitAndNeverForRollback() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            RoomAccessRevocationPublisher publisher = context.getBean(RoomAccessRevocationPublisher.class);
            StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
            PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
            CountDownLatch sent = new CountDownLatch(1);
            when(redis.convertAndSend(anyString(), any(Object.class))).thenAnswer(invocation -> {
                sent.countDown();
                return 1L;
            });
            RoomAccessRevoked event = RoomAccessRevoked.memberLeft(UUID.randomUUID(), UUID.randomUUID());

            new TransactionTemplate(transactionManager).execute(status -> {
                publisher.publishAfterCommit(event);
                verify(redis, never()).convertAndSend(anyString(), any(Object.class));
                return null;
            });
            assertTrue(sent.await(3, TimeUnit.SECONDS), "committed event did not reach Redis adapter");
            verify(redis, times(1)).convertAndSend(eq("wooms.realtime.access.v1"), any(Object.class));

            reset(redis);
            new TransactionTemplate(transactionManager).execute(status -> {
                publisher.publishAfterCommit(RoomAccessRevoked.memberLeft(UUID.randomUUID(), UUID.randomUUID()));
                status.setRollbackOnly();
                return null;
            });
            Thread.sleep(150);
            verify(redis, never()).convertAndSend(anyString(), any(Object.class));
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean PlatformTransactionManager transactionManager() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:h2:mem:roomrevocationtest;DB_CLOSE_DELAY=-1", "sa", "");
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean StringRedisTemplate stringRedisTemplate() { return mock(StringRedisTemplate.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().registerModule(new JavaTimeModule()); }
        @Bean RealtimeAccessProperties realtimeAccessProperties() { return new RealtimeAccessProperties(); }
        @Bean ThreadPoolTaskExecutor realtimeRevocationExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(4);
            executor.setThreadNamePrefix("room-revocation-test-");
            executor.initialize();
            return executor;
        }
        @Bean RoomAccessRevocationPublisher publisher(ApplicationEventPublisher events) {
            return new RoomAccessRevocationEventAdapter(events);
        }
        @Bean RedisRoomAccessRevocationRelay relay(StringRedisTemplate redis, ObjectMapper mapper,
                                                    RealtimeAccessProperties properties,
                                                    ThreadPoolTaskExecutor realtimeRevocationExecutor) {
            return new RedisRoomAccessRevocationRelay(redis, mapper, properties, realtimeRevocationExecutor);
        }
    }
}
