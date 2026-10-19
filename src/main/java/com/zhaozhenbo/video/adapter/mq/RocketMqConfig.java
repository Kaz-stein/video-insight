package com.zhaozhenbo.video.adapter.mq;

import com.zhaozhenbo.video.application.TaskPipelineService;
import com.zhaozhenbo.video.port.TaskQueuePort;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 只有 video.mq.enabled=true 时才连 broker。
 * 这样 `mvn test` 和无 MQ 的本地开发不会被一个没启动的中间件卡住。
 */
@Configuration
@ConditionalOnProperty(prefix = "video.mq", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RocketMqProperties.class)
public class RocketMqConfig {

    @Bean(destroyMethod = "shutdown")
    public DefaultMQProducer videoTaskProducer(RocketMqProperties properties) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer(properties.getProducerGroup());
        producer.setNamesrvAddr(properties.getNamesrvAddr());
        producer.setSendMsgTimeout(3000);
        producer.start();
        return producer;
    }

    @Bean(destroyMethod = "shutdown")
    public DefaultMQPushConsumer videoTaskConsumer(RocketMqProperties properties,
                                                   TaskPipelineService pipeline,
                                                   ObjectMapper objectMapper) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(properties.getConsumerGroup());
        consumer.setNamesrvAddr(properties.getNamesrvAddr());
        consumer.setConsumeThreadMin(properties.getConsumeThreadMin());
        consumer.setConsumeThreadMax(properties.getConsumeThreadMax());
        // 基础设施异常最多让 broker 重投 3 次；业务重试由我们自己控制节奏
        consumer.setMaxReconsumeTimes(3);
        consumer.subscribe(properties.getTopic(), "*");
        consumer.registerMessageListener(new TaskMessageListener(pipeline, objectMapper));
        consumer.start();
        return consumer;
    }

    @Bean
    public TaskQueuePort taskQueuePort(DefaultMQProducer videoTaskProducer,
                                       RocketMqProperties properties,
                                       ObjectMapper objectMapper) {
        return new RocketMqTaskQueueAdapter(videoTaskProducer, properties, objectMapper);
    }
}
