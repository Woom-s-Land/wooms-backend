package com.ee06.wooms.domain.chat.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    // 🔧 실제 쿠키 이름으로 바꿔줘 (예: "ACCESS_TOKEN" 또는 "WOOMS_AT")
    private static final String COOKIE_NAME = "Authorization";
    public static final String ATTR_JWT_TOKEN = "JWT_TOKEN";

    @Override
    public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res,
                                   WebSocketHandler wsHandler, Map<String, Object> attrs) {
        if (req instanceof ServletServerHttpRequest sreq) {
            var http = sreq.getServletRequest();

            Cookie[] cookies = http.getCookies();
            if (cookies != null) {
                for (Cookie c : cookies) {
                    if (!COOKIE_NAME.equals(c.getName())) continue;

                    String raw = c.getValue();
                    String value = raw != null ? URLDecoder.decode(raw, StandardCharsets.UTF_8) : null;

                    if (value != null && value.startsWith("Bearer ")) {
                        value = value.substring(7);
                    }

                    if (value != null && !value.isBlank()) {
                        attrs.put(ATTR_JWT_TOKEN, value);
                        log.info("[HS] Found JWT cookie: {} (len={})", COOKIE_NAME, value.length());
                    } else {
                        log.info("[HS] {} cookie present but empty", COOKIE_NAME);
                    }
                    break;
                }
            } else {
                log.info("[HS] No cookies on handshake");
            }

        }
        return true; // 쿠키 없어도 연결 자체는 열어둔다(권한은 이후 단계에서 제한)
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
