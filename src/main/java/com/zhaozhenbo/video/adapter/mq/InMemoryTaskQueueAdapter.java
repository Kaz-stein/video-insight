package com.zhaozhenbo.video.adapter.mq;

import com.zhaozhenbo.video.domain.RetryDelay;
import com.zhaozhenbo.video.domain.TaskMessage;
import com.zhaozhenbo.video.port.TaskQueuePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 内存队列：只用于本地测试/无 broker 的开发环境。
 *
 * 它不提供持久化，进程一挂消息就没了；所以它不是“降级方案”，
 * 只是让队列逻辑可以在没有中间件时做确定性测试。
 */
@Component
@ConditionalOnProperty(prefix = "video.mq", name = "enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryTaskQueueAdapter implements TaskQueuePort {

    private final BlockingQueue<TaskMessage> queue = new LinkedBlockingQueue<>();
    private final List<RetryRecord> retries = new CopyOnWriteArrayList<>();
    private final List<DeadLetterRecord> deadLetters = new CopyOnWriteArrayList<>();

    @Override
    public void enqueue(TaskMessage message) {
        queue.add(message);
    }

    @Override
    public void enqueueRetry(TaskMessage message, RetryDelay delay) {
        retries.add(new RetryRecord(message, delay));
        queue.add(message);
    }

    @Override
    public void sendToDeadLetter(TaskMessage message, String reason) {
        deadLetters.add(new DeadLetterRecord(message, reason));
    }

    public TaskMessage poll() {
        return queue.poll();
    }

    public int size() {
        return queue.size();
    }

    public List<RetryRecord> retries() {
        return List.copyOf(retries);
    }

    public List<DeadLetterRecord> deadLetters() {
        return List.copyOf(deadLetters);
    }

    /** 测试隔离用：清空队列和观测记录 */
    public void clear() {
        queue.clear();
        retries.clear();
        deadLetters.clear();
    }

    public record RetryRecord(TaskMessage message, RetryDelay delay) {
    }

    public record DeadLetterRecord(TaskMessage message, String reason) {
    }
}
