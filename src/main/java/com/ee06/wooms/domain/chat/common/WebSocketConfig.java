package com.ee06.wooms.domain.chat.common;

import com.ee06.wooms.domain.chat.exception.StompErrorHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

@RequiredArgsConstructor
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompChannelInterceptor stompChannelInterceptor;
    private final StompErrorHandler errorHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    @Bean
    public ThreadPoolTaskScheduler wsTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("wss-heartbeat-");
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setErrorHandler(errorHandler)
                .addEndpoint("/ws")
                .addInterceptors(jwtHandshakeInterceptor)
            .setAllowedOrigins("http://localhost:5173","http://localhost:3000","https://wooms.duckdns.org");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/ws/send")
                .enableSimpleBroker("/ws/wooms", "/ws/wooms/disconnect", "/queue")
                .setTaskScheduler(wsTaskScheduler())
                .setHeartbeatValue(new long[]{10_000, 10_000});

        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompChannelInterceptor);
    }
}
