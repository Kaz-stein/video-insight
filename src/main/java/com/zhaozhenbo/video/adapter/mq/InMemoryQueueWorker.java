package com.zhaozhenbo.video.adapter.mq;

import com.zhaozhenbo.video.application.TaskPipelineService;
import com.zhaozhenbo.video.domain.TaskMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地模式消费者：没有 RocketMQ 时，用一个虚拟线程消费内存队列。
 *
 * 它只解决“本地演示不依赖 broker”，不代表生产可靠性；
 * 生产打开 MQ_ENABLED=true 后这个 worker 不会创建。
 */
public class InMemoryQueueWorker {

    private static final Logger log = LoggerFactory.getLogger(InMemoryQueueWorker.class);

    private final InMemoryTaskQueueAdapter queue;
    private final ObjectProvider<TaskPipelineService> pipeline;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public InMemoryQueueWorker(InMemoryTaskQueueAdapter queue,
                               ObjectProvider<TaskPipelineService> pipeline) {
        this.queue = queue;
        this.pipeline = pipeline;
    }

    @PostConstruct
    void start() {
        Thread.ofVirtual().name("in-memory-task-worker").start(this::loop);
    }

    @PreDestroy
    void stop() {
        running.set(false);
    }

    private void loop() {
        while (running.get()) {
            try {
                TaskMessage message = queue.poll(500, TimeUnit.MILLISECONDS);
                if (message != null) {
                    pipeline.getObject().handle(message);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // 单条任务异常不能杀死 worker；TaskPipelineService 内部已处理业务失败
                log.error("本地内存 worker 处理异常", e);
            }
        }
    }
}
