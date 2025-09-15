package com.ee06.wooms.global.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@Component
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger httpLog = LoggerFactory.getLogger("http.request");

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true; // 비동기 디스패치 중복 로깅 방지
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        final String traceId = Optional.ofNullable(req.getHeader("X-Trace-Id"))
                .orElse(UUID.randomUUID().toString());

        long t0 = System.currentTimeMillis();
        Object pattern = req.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);

        MDC.put("traceId", traceId);
        MDC.put("method", req.getMethod());
        MDC.put("path_template", pattern != null ? pattern.toString() : req.getRequestURI());

        try {
            chain.doFilter(req, res);
        } finally {
            long took = System.currentTimeMillis() - t0;
            MDC.put("status", String.valueOf(res.getStatus()));
            httpLog.info("request_complete durationMs={}", took);
            MDC.clear();
        }
    }
}
