package com.ee06.wooms.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS is handled by Spring Security via http.cors() + the CorsConfigurationSource
 * bean in {@link CorsConfig}. The old manual CorsFilter registration was removed
 * because it ran inside the security chain (so it missed security-terminated
 * responses like login success) and duplicated CORS headers.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
}