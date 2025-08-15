package com.ee06.wooms.global.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

@Component
public class CookieUtils {

    private static boolean secure;

    private static String samesite;

    @Value("${cookie.secure}")
    public void setSecure(String secure) {
        this.secure = Boolean.valueOf(secure);
    }

    @Value("${cookie.samesite}")
    public void setSamesite(String samesite) {
        this.samesite = samesite;
    }

    public static String getCookie(HttpServletRequest request, String name) {
        return Optional.ofNullable(request.getCookies())
                .flatMap(cookies -> Arrays.stream(cookies)
                        .filter(cookie -> Objects.equals(name, cookie.getName()))
                        .map(Cookie::getValue)
                        .findAny())
                .orElse(null);
    }

    public static void addCookie(HttpServletResponse response, String name, String value, int maxAge) {
        System.out.println(secure);
        System.out.println(samesite);

        ResponseCookie cookie = ResponseCookie.from(name, value)
                .path("/")
                .sameSite(samesite)
                .httpOnly(secure)
                .secure(secure)
                .maxAge(maxAge)
                .build();

        response.addHeader("Set-Cookie", cookie.toString());
    }
}



