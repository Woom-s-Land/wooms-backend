package com.ee06.wooms.global.config;

import com.ee06.wooms.domain.users.service.CustomOAuth2UserService;
import com.ee06.wooms.global.jwt.JWTUtil;
import com.ee06.wooms.global.jwt.filter.ChannelJoinFilter;
import com.ee06.wooms.global.jwt.filter.CustomLogoutFilter;
import com.ee06.wooms.global.jwt.filter.JWTFilter;
import com.ee06.wooms.global.jwt.filter.JwtAuthenticationEntryPoint;
import com.ee06.wooms.global.jwt.filter.LoginFilter;
import com.ee06.wooms.global.jwt.repository.RefreshTokenRepository;
import com.ee06.wooms.global.oauth.CustomSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final JWTUtil jwtUtil;

    private final CustomSuccessHandler customSuccessHandler;
    private final CustomOAuth2UserService customOAuth2UserService;

    private final AuthenticationConfiguration authenticationConfiguration;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final RefreshTokenRepository refreshTokenRepository;

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return web -> web
                .ignoring()
                .requestMatchers("/swagger-ui/**", "/swagger-resources/**", "/v3/api-docs/**"
                ,"/actuator/**", "/images/**", "/js/**", "/css/**", "/ws/**", "/grafana/**");
    }

    private static final String[] WHITE_LIST = {
            "/api/auth", "/api/auth/**", "/api/authorization/users",
            "/api/oauth2/authorization/**", "/api/login/oauth2/code/**",
            "/error"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests((authorizeRequest) -> authorizeRequest
                        .requestMatchers(WHITE_LIST).permitAll()
                        .anyRequest().authenticated()
                )
                .headers((headersConfigurer) ->
                        headersConfigurer.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
                )
                .oauth2Login((oauth2) -> oauth2
                        .authorizationEndpoint(endpoint -> endpoint.baseUri("/api/oauth2/authorization/"))
                        .userInfoEndpoint((userInfoEndpointConfig) -> userInfoEndpointConfig.userService(customOAuth2UserService))
                        .successHandler(customSuccessHandler)
                        .loginProcessingUrl("/api/login/oauth2/code/*")
                )
                .sessionManagement((session) -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .with(new Custom(
                        authenticationConfiguration,
                        jwtAuthenticationEntryPoint,
                        refreshTokenRepository,
                        jwtUtil), Custom::getClass
                );

        return http.build();
    }

    @RequiredArgsConstructor
    public static class Custom extends AbstractHttpConfigurer<Custom, HttpSecurity> {
        private final AuthenticationConfiguration authenticationConfiguration;
        private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
        private final RefreshTokenRepository refreshTokenRepository;
        private final JWTUtil jwtUtil;


        @Override
        public void configure(HttpSecurity http) throws Exception {
            ChannelJoinFilter channelJoinFilter = new ChannelJoinFilter(jwtUtil);
            JWTFilter jwtFilter = new JWTFilter(jwtUtil, jwtAuthenticationEntryPoint);
            LoginFilter loginFilter = new LoginFilter(authenticationManager(authenticationConfiguration), refreshTokenRepository, jwtAuthenticationEntryPoint, jwtUtil);
            loginFilter.setFilterProcessesUrl("/api/auth");
            loginFilter.setPostOnly(true);

            http
                    .addFilterAt(loginFilter, UsernamePasswordAuthenticationFilter.class)
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .addFilterBefore(channelJoinFilter, LoginFilter.class)
                    .addFilterBefore(new CustomLogoutFilter(jwtUtil, refreshTokenRepository), LogoutFilter.class)
                    .exceptionHandling((handler) -> handler.authenticationEntryPoint(jwtAuthenticationEntryPoint));
        }
    }

    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public static AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
