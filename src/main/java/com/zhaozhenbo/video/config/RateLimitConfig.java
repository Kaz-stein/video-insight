package com.zhaozhenbo.video.config;

import com.zhaozhenbo.video.adapter.redis.InMemoryTokenBucketRateLimiter;
import com.zhaozhenbo.video.adapter.redis.RedisTokenBucketRateLimiter;
import com.zhaozhenbo.video.port.RateLimiterPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    @ConditionalOnProperty(name = "video.rate-limit.mode", havingValue = "redis")
    public RateLimiterPort redisRateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        return new RedisTokenBucketRateLimiter(redis, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "video.rate-limit.mode", havingValue = "memory", matchIfMissing = true)
    public RateLimiterPort memoryRateLimiter(RateLimitProperties properties) {
        return new InMemoryTokenBucketRateLimiter(properties);
    }
}
