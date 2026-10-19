package com.zhaozhenbo.video.port;

import com.zhaozhenbo.video.domain.RetryDelay;
import com.zhaozhenbo.video.domain.TaskMessage;

/**
 * 任务队列端口。
 *
 * 应用层只依赖这个接口，不认 RocketMQ。测试用内存实现，
 * 生产用 RocketMQ 实现；将来换 Kafka/RabbitMQ 也只动 adapter。
 */
public interface TaskQueuePort {

    /** 首次投递（或补偿重投） */
    void enqueue(TaskMessage message);

    /** 失败后的延迟重试 */
    void enqueueRetry(TaskMessage message, RetryDelay delay);

    /** 超过最大重试次数，投递到死信 */
    void sendToDeadLetter(TaskMessage message, String reason);
}
