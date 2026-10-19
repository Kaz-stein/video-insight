package com.zhaozhenbo.video.adapter.mq;

import com.zhaozhenbo.video.domain.RetryDelay;
import com.zhaozhenbo.video.domain.TaskMessage;
import com.zhaozhenbo.video.port.TaskQueueException;
import com.zhaozhenbo.video.port.TaskQueuePort;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import tools.jackson.databind.ObjectMapper;

/**
 * RocketMQ 生产者适配器。
 *
 * 消息 key 用 taskId：broker 控制台能按 key 查“这个任务投递了几次”，
 * 排障比翻日志快。
 */
public class RocketMqTaskQueueAdapter implements TaskQueuePort {

    private final DefaultMQProducer producer;
    private final RocketMqProperties properties;
    private final ObjectMapper objectMapper;

    public RocketMqTaskQueueAdapter(DefaultMQProducer producer,
                                    RocketMqProperties properties,
                                    ObjectMapper objectMapper) {
        this.producer = producer;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void enqueue(TaskMessage message) {
        send(properties.getTopic(), message, 0);
    }

    @Override
    public void enqueueRetry(TaskMessage message, RetryDelay delay) {
        send(properties.getTopic(), message, delay.rocketMqLevel());
    }

    @Override
    public void sendToDeadLetter(TaskMessage message, String reason) {
        try {
            send(properties.getDlqTopic(), message, 0, reason);
        } catch (Exception first) {
            // 某些 broker 不允许应用直接写 %DLQ% 系统 Topic，退到业务死信 Topic，
            // 消息不会丢，Topic 名称只影响运维脚本怎么捞。
            String fallback = properties.getTopic() + "-dlq";
            try {
                send(fallback, message, 0, reason);
            } catch (Exception second) {
                first.addSuppressed(second);
                throw new TaskQueueException("死信投递失败: " + properties.getDlqTopic(), first);
            }
        }
    }

    private void send(String topic, TaskMessage message, int delayLevel) {
        send(topic, message, delayLevel, null);
    }

    private void send(String topic, TaskMessage message, int delayLevel, String reason) {
        TaskQueueException last = null;
        // RocketMQ 自带的重试窗口只有 ~200ms，遇到“Topic 刚创建/主从切换”这种
        // 秒级抖动不够用。这里做 3 次退避重试；正常路径仍然只发一次，不影响 P95。
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                doSend(topic, message, delayLevel, reason);
                return;
            } catch (Exception e) {
                last = new TaskQueueException(
                        "发送消息失败 topic=" + topic + " taskId=" + message.taskId() + " attempt=" + attempt, e);
                sleepBeforeRetry(attempt);
            }
        }
        throw last;
    }

    private void doSend(String topic, TaskMessage message, int delayLevel, String reason) throws Exception {
        Message mqMessage = new Message(
                topic,
                "TASK",
                String.valueOf(message.taskId()),
                objectMapper.writeValueAsBytes(message));
        if (delayLevel > 0) {
            mqMessage.setDelayTimeLevel(delayLevel);
        }
        if (reason != null) {
            mqMessage.putUserProperty("reason", truncate(reason));
        }
        producer.send(mqMessage);
    }

    private static void sleepBeforeRetry(int attempt) {
        if (attempt >= 3) {
            return;
        }
        try {
            Thread.sleep(300L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskQueueException("发送重试被中断", e);
        }
    }

    private static String truncate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 500);
    }
}
