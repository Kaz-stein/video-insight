package com.zhaozhenbo.video.adapter.redis;

import com.zhaozhenbo.video.config.RateLimitProperties;
import com.zhaozhenbo.video.port.RateLimiterPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/**
 * Redis 令牌桶。
 *
 * 必须用 Lua：如果客户端分 GET、算、SET 三步，两个实例会同时看到旧 tokens，
 * 并发下会超发。Lua 在 Redis 单线程里一次完成读-算-写。
 */
public class RedisTokenBucketRateLimiter implements RateLimiterPort {

    private static final String LUA = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local permits = tonumber(ARGV[4])

            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            end

            local elapsed = math.max(0, now - ts)
            tokens = math.min(capacity, tokens + elapsed / 1000.0 * refill)

            local allowed = 0
            local retry = 0
            if tokens >= permits then
              tokens = tokens - permits
              allowed = 1
            else
              retry = math.ceil((permits - tokens) / refill * 1000)
            end

            redis.call('HMSET', key, 'tokens', tokens, 'ts', now)
            redis.call('PEXPIRE', key, 3600000)
            return {allowed, math.floor(tokens), retry}
            """;

    private static final DefaultRedisScript<List> SCRIPT =
            new DefaultRedisScript<>(LUA, List.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public RateLimitDecision tryAcquire(String key, int permits) {
        List<?> result = redis.execute(
                SCRIPT,
                List.of(properties.getKeyPrefix() + ":" + key),
                String.valueOf(properties.getCapacity()),
                String.valueOf(properties.getRefillPerSecond()),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(permits));
        if (result == null || result.size() < 3) {
            throw new IllegalStateException("Redis 令牌桶脚本返回异常: " + result);
        }
        boolean allowed = number(result.get(0)) == 1;
        long remaining = number(result.get(1));
        long retryAfterMs = number(result.get(2));
        return new RateLimitDecision(allowed, remaining, retryAfterMs);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }
}
