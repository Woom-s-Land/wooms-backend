package com.ee06.wooms.domain.chat.common;

import com.ee06.wooms.domain.chat.exception.StompErrorHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

@RequiredArgsConstructor
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final HttpSessionHandshakeInterceptor customHandshakeInterceptor;
    private final StompErrorHandler errorHandler;
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setErrorHandler(errorHandler).addEndpoint("/ws")
                .addInterceptors(customHandshakeInterceptor)
            .setAllowedOrigins("http://localhost:5173","http://localhost:3000","https://wooms.duckdns.org");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/ws/send");  //클라이언트에서 보낸 메세지를 받을 prefix
        registry.enableSimpleBroker("/ws/wooms"); //해당 주소를 구독하고 있는 클라이언트들에게 메세지 전달
    }
//    @Override
//    public void configureClientInboundChannel(ChannelRegistration registration) {
//        registration.interceptors(interceptors);
//    }
}
