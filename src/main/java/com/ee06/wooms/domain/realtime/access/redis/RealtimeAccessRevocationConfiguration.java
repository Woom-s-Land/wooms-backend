package com.ee06.wooms.domain.realtime.access.redis;

import com.ee06.wooms.domain.realtime.access.RoomAccessRevocationEventAdapter;
import com.ee06.wooms.domain.realtime.access.port.RoomAccessRevocationPublisher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableConfigurationProperties(RealtimeAccessProperties.class)
public class RealtimeAccessRevocationConfiguration {
    @Bean
    RoomAccessRevocationPublisher roomAccessRevocationPublisher(ApplicationEventPublisher events) {
        return new RoomAccessRevocationEventAdapter(events);
    }

    @Bean(name = "realtimeRevocationExecutor")
    ThreadPoolTaskExecutor realtimeRevocationExecutor(RealtimeAccessProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(Math.max(1, properties.getPublishQueueCapacity()));
        executor.setThreadNamePrefix("room-revocation-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
