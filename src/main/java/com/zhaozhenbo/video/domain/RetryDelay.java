package com.zhaozhenbo.video.domain;

/**
 * 重试节奏：1s → 5s → 30s。
 *
 * RocketMQ 的延迟级别是固定档位（1s=1, 5s=2, 10s=3, 30s=4），
 * 这里把“业务语义”和“中间件档位”隔开：以后换 MQ，只改 level 映射。
 */
public enum RetryDelay {
    ONE_SECOND(1),
    FIVE_SECONDS(2),
    THIRTY_SECONDS(4);

    private final int rocketMqLevel;

    RetryDelay(int rocketMqLevel) {
        this.rocketMqLevel = rocketMqLevel;
    }

    public int rocketMqLevel() {
        return rocketMqLevel;
    }

    /** 第 N 次执行失败后，下一次重试该等多久 */
    public static RetryDelay afterFailure(int failedAttempt) {
        return switch (failedAttempt) {
            case 1 -> ONE_SECOND;
            case 2 -> FIVE_SECONDS;
            case 3 -> THIRTY_SECONDS;
            default -> throw new IllegalArgumentException("没有第 " + failedAttempt + " 次失败对应的重试档位");
        };
    }
}
