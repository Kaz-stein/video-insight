package com.zhaozhenbo.video.adapter.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RocketMQ 连接参数。全部走配置，不写死在代码里。
 */
@ConfigurationProperties(prefix = "video.mq")
public class RocketMqProperties {

    private boolean enabled = false;
    private String namesrvAddr = "127.0.0.1:9876";
    private String topic = "video-task";
    /**
     * 应用级死信 Topic。默认与业务消费者组同名，便于和 RocketMQ 自带的
     * %DLQ%<consumerGroup> 对齐；如果 broker 不允许直接写这个 Topic，
     * 配置成 video-task-dlq 即可。
     */
    private String dlqTopic = "%DLQ%video-task";
    private String producerGroup = "video-task-producer";
    private String consumerGroup = "video-task";
    private int consumeThreadMin = 4;
    private int consumeThreadMax = 16;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNamesrvAddr() {
        return namesrvAddr;
    }

    public void setNamesrvAddr(String namesrvAddr) {
        this.namesrvAddr = namesrvAddr;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getDlqTopic() {
        return dlqTopic;
    }

    public void setDlqTopic(String dlqTopic) {
        this.dlqTopic = dlqTopic;
    }

    public String getProducerGroup() {
        return producerGroup;
    }

    public void setProducerGroup(String producerGroup) {
        this.producerGroup = producerGroup;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public int getConsumeThreadMin() {
        return consumeThreadMin;
    }

    public void setConsumeThreadMin(int consumeThreadMin) {
        this.consumeThreadMin = consumeThreadMin;
    }

    public int getConsumeThreadMax() {
        return consumeThreadMax;
    }

    public void setConsumeThreadMax(int consumeThreadMax) {
        this.consumeThreadMax = consumeThreadMax;
    }
}
