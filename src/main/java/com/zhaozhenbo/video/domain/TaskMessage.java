package com.zhaozhenbo.video.domain;

/**
 * MQ 消息体：只放“执行哪个任务、第几次尝试”，不放业务数据。
 *
 * 为什么不让消息携带字幕/路径：
 *   - 消息越大，broker 压力越大，重投成本越高；
 *   - 业务数据以数据库为准，消息只是“去数据库取任务”的通知；
 *   - 消费端崩溃重投时，重新读库能得到最新状态，不会拿到过期快照。
 */
public record TaskMessage(long taskId, int attempt) {

    public TaskMessage {
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId 必须为正数: " + taskId);
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt 从 1 开始: " + attempt);
        }
    }

    public TaskMessage nextAttempt() {
        return new TaskMessage(taskId, attempt + 1);
    }
}
