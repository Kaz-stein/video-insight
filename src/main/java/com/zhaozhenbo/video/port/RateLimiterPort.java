package com.zhaozhenbo.video.port;

public interface RateLimiterPort {

    RateLimitDecision tryAcquire(String key, int permits);

    record RateLimitDecision(boolean allowed, long remaining, long retryAfterMs) {
    }
}
