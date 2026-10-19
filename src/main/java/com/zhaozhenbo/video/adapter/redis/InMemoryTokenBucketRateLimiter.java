package com.zhaozhenbo.video.adapter.redis;

import com.zhaozhenbo.video.config.RateLimitProperties;
import com.zhaozhenbo.video.port.RateLimiterPort;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 单机内存令牌桶：测试和无 Redis 环境使用。
 * 多实例部署必须用 Redis 实现，否则每个实例各算一份，限流会失效。
 */
public class InMemoryTokenBucketRateLimiter implements RateLimiterPort {

    private final RateLimitProperties properties;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public InMemoryTokenBucketRateLimiter(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    public RateLimitDecision tryAcquire(String key, int permits) {
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(
                properties.getCapacity(), System.nanoTime()));
        synchronized (bucket) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - bucket.lastNanos) / 1_000_000_000.0;
            bucket.tokens = Math.min(properties.getCapacity(),
                    bucket.tokens + elapsedSeconds * properties.getRefillPerSecond());
            bucket.lastNanos = now;

            if (bucket.tokens >= permits) {
                bucket.tokens -= permits;
                return new RateLimitDecision(true, (long) bucket.tokens, 0);
            }

            double refill = Math.max(0.001, properties.getRefillPerSecond());
            long retryAfterMs = (long) Math.ceil((permits - bucket.tokens) / refill * 1000);
            return new RateLimitDecision(false, (long) bucket.tokens, Math.max(1, retryAfterMs));
        }
    }

    private static final class Bucket {
        private double tokens;
        private long lastNanos;

        private Bucket(double tokens, long lastNanos) {
            this.tokens = tokens;
            this.lastNanos = lastNanos;
        }
    }
}
