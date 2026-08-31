package com.usermgmt.identity.ratelimit;

import com.usermgmt.identity.common.ClientIp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-IP token-bucket rate limiter for the unauthenticated auth endpoints, protecting against
 * brute-force and abuse (credential stuffing, OTP/reset spamming, account enumeration).
 *
 * <p>A separate bucket is kept per (client IP, request path), so hammering one endpoint does not
 * lock a client out of the others. Buckets live in memory — fine for a single instance or with a
 * sticky/shared limiter in front; a distributed deployment would back this with Redis. Idle
 * (refilled-to-full) buckets are swept periodically so the map stays bounded to active clients.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long SWEEP_INTERVAL_MILLIS = 60_000;

    private final boolean enabled;
    private final int capacity;
    private final int refillPeriodSeconds;
    private final double refillTokensPerMillis;
    private final Counter blocked;

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastSweep = new AtomicLong(System.currentTimeMillis());

    public RateLimitFilter(boolean enabled, int capacity, int refillPeriodSeconds, MeterRegistry meterRegistry) {
        this.enabled = enabled;
        this.capacity = capacity;
        this.refillPeriodSeconds = refillPeriodSeconds;
        this.refillTokensPerMillis = capacity / (refillPeriodSeconds * 1000.0);
        this.blocked = Counter.builder("identity.ratelimit.blocked")
                .description("Requests rejected by the per-IP rate limiter")
                .register(meterRegistry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Only throttle state-changing auth calls; let preflights and reads through untouched.
        if (!enabled || !"POST".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String key = ClientIp.of(request) + " " + request.getRequestURI();
        TokenBucket bucket = buckets.computeIfAbsent(key,
                k -> new TokenBucket(capacity, refillTokensPerMillis));
        maybeSweep();

        if (bucket.tryConsume()) {
            chain.doFilter(request, response);
        } else {
            tooManyRequests(request, response);
        }
    }

    private void tooManyRequests(HttpServletRequest request, HttpServletResponse response) throws IOException {
        blocked.increment();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(refillPeriodSeconds));
        // Hand-built to match the ApiError shape without coupling the filter to a JSON library.
        String body = "{\"timestamp\":\"" + Instant.now() + "\","
                + "\"status\":" + HttpStatus.TOO_MANY_REQUESTS.value() + ","
                + "\"error\":\"Too Many Requests\","
                + "\"message\":\"Too many requests. Please slow down and try again shortly.\","
                + "\"path\":\"" + escapeJson(request.getRequestURI()) + "\"}";
        response.getWriter().write(body);
    }

    private void maybeSweep() {
        long now = System.currentTimeMillis();
        long previous = lastSweep.get();
        if (now - previous >= SWEEP_INTERVAL_MILLIS && lastSweep.compareAndSet(previous, now)) {
            // A full bucket is indistinguishable from a fresh one, so evicting it is safe.
            buckets.values().removeIf(TokenBucket::isFull);
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** A simple thread-safe token bucket: {@code capacity} burst, refilling continuously. */
    private static final class TokenBucket {

        private final double capacity;
        private final double refillTokensPerMillis;
        private double tokens;
        private long lastRefill;

        TokenBucket(double capacity, double refillTokensPerMillis) {
            this.capacity = capacity;
            this.refillTokensPerMillis = refillTokensPerMillis;
            this.tokens = capacity;
            this.lastRefill = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized boolean isFull() {
            refill();
            return tokens >= capacity;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            tokens = Math.min(capacity, tokens + (now - lastRefill) * refillTokensPerMillis);
            lastRefill = now;
        }
    }
}
