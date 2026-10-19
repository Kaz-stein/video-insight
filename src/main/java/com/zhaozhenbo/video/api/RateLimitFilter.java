package com.zhaozhenbo.video.api;

import com.zhaozhenbo.video.port.RateLimiterPort;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 只对 POST /api/tasks 限流：这是唯一会触发长任务和模型费用的入口。
 *
 * Redis 不可用时 fail-open（放行并记日志），避免限流组件把主业务拖垮；
 * 安全要求极高的场景可以改成 fail-closed。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiterPort limiter;

    public RateLimitFilter(RateLimiterPort limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !"/api/tasks".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String user = request.getHeader("X-User-Id");
        if (user == null || user.isBlank()) {
            user = "anonymous";
        }

        RateLimiterPort.RateLimitDecision decision;
        try {
            decision = limiter.tryAcquire(user, 1);
        } catch (Exception e) {
            log.warn("限流器不可用，本次放行 user={}", user, e);
            chain.doFilter(request, response);
            return;
        }

        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            long retryAfterSeconds = Math.max(1, (decision.retryAfterMs() + 999) / 1000);
            response.setStatus(429);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.getWriter().write("{\"error\":\"请求过于频繁\",\"retryAfterMs\":"
                    + decision.retryAfterMs() + "}");
            return;
        }
        chain.doFilter(request, response);
    }
}
