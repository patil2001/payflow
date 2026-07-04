package com.payflow.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token-bucket rate limiter, per authenticated user (falls back to client IP).
 *
 * Talking points:
 *  - Token bucket allows short bursts (capacity) while capping sustained rate (refill).
 *  - Lock-free: bucket state packed into a single AtomicLong (tokens x1e6 + last refill nanos
 *    would need two words, so we use a small synchronized block per bucket instead of a global lock).
 *  - In a multi-node deployment this state moves to Redis (INCR + EXPIRE or a Lua script)
 *    so all nodes share one bucket per user.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final long capacity;
    private final long refillPerSecond;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${payflow.rate-limit.capacity}") long capacity,
                           @Value("${payflow.rate-limit.refill-per-second}") long refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = resolveKey(request);
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity));

        if (bucket.tryConsume(capacity, refillPerSecond)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate_limit_exceeded\",\"message\":\"Too many requests\"}");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/h2-console") || path.startsWith("/actuator");
    }

    private String resolveKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() != null) {
            return "user:" + auth.getPrincipal();
        }
        return "ip:" + request.getRemoteAddr();
    }

    static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        Bucket(long capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume(long capacity, long refillPerSecond) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
            lastRefillNanos = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
