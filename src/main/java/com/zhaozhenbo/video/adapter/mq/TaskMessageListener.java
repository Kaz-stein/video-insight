package com.zhaozhenbo.video.adapter.mq;

import com.zhaozhenbo.video.application.TaskPipelineService;
import com.zhaozhenbo.video.domain.TaskMessage;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 消费者入口。
 *
 * 业务失败在 TaskPipelineService 内部已经转成“延迟重试/死信”，
 * 这里返回 RECONSUME_LATER 只代表基础设施异常（比如数据库连不上），
 * 交给 broker 做一次底层重投。
 */
public class TaskMessageListener implements MessageListenerConcurrently {

    private static final Logger log = LoggerFactory.getLogger(TaskMessageListener.class);

    private final TaskPipelineService pipeline;
    private final ObjectMapper objectMapper;

    public TaskMessageListener(TaskPipelineService pipeline, ObjectMapper objectMapper) {
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
    }

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages,
                                                    ConsumeConcurrentlyContext context) {
        for (MessageExt message : messages) {
            try {
                TaskMessage taskMessage = objectMapper.readValue(message.getBody(), TaskMessage.class);
                pipeline.handle(taskMessage);
            } catch (Exception e) {
                log.error("消息处理发生基础设施异常，交回 broker 重投 msgId={}", message.getMsgId(), e);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
