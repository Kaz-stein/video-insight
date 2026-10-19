package com.zhaozhenbo.video.config;

import com.zhaozhenbo.video.adapter.mq.InMemoryQueueWorker;
import com.zhaozhenbo.video.adapter.mq.InMemoryTaskQueueAdapter;
import com.zhaozhenbo.video.application.TaskPipelineService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "video.mq", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalQueueConfig {

    @Bean
    @ConditionalOnProperty(prefix = "video.mq", name = "in-memory-worker",
            havingValue = "true", matchIfMissing = true)
    public InMemoryQueueWorker inMemoryQueueWorker(
            InMemoryTaskQueueAdapter queue,
            ObjectProvider<TaskPipelineService> pipeline) {
        return new InMemoryQueueWorker(queue, pipeline);
    }
}
