package com.zhaozhenbo.video.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "video.rate-limit")
public class RateLimitProperties {

    /** memory 用于测试/无 Redis；redis 用于真实令牌桶 */
    private String mode = "memory";
    private int capacity = 10;
    private double refillPerSecond = 2;
    private String keyPrefix = "rate:api";

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public double getRefillPerSecond() {
        return refillPerSecond;
    }

    public void setRefillPerSecond(double refillPerSecond) {
        this.refillPerSecond = refillPerSecond;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
