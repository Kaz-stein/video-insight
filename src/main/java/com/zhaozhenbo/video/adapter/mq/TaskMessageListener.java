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
            TaskMessage taskMessage;
            try {
                taskMessage = objectMapper.readValue(message.getBody(), TaskMessage.class);
            } catch (Exception parseError) {
                // 毒消息：重投多少次也不会变成合法 JSON，继续 RECONSUME_LATER 会把队列卡死，
                // 所以这里直接记录并确认消费，把它从队列里摘掉。
                log.error("丢弃无法反序列化的消息 msgId={} body前80字节={}",
                        message.getMsgId(), preview(message.getBody()), parseError);
                continue;
            }
            try {
                pipeline.handle(taskMessage);
            } catch (Exception e) {
                log.error("消息处理发生基础设施异常，交回 broker 重投 msgId={}", message.getMsgId(), e);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    private static String preview(byte[] body) {
        if (body == null) return "null";
        int len = Math.min(body.length, 80);
        return new String(body, 0, len, java.nio.charset.StandardCharsets.UTF_8);
    }
}
