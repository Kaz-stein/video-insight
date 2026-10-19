package com.zhaozhenbo.video.application;

import com.zhaozhenbo.video.adapter.persistence.VideoTaskEntity;
import com.zhaozhenbo.video.adapter.persistence.VideoTaskRepository;
import com.zhaozhenbo.video.adapter.persistence.StageLogRepository;
import com.zhaozhenbo.video.domain.TaskMessage;
import com.zhaozhenbo.video.domain.TaskStatus;
import com.zhaozhenbo.video.port.TaskQueuePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发送补偿：先落库、后发消息，所以“发消息失败”不会丢任务。
 *
 * 每 30 秒扫一次 PENDING 且超过宽限期的任务，重新投递。
 * 重复投递不会重复执行——消费者用 affected rows 抢锁。
 */
@Component
public class TaskCompensationJob {

    private static final Logger log = LoggerFactory.getLogger(TaskCompensationJob.class);

    private final VideoTaskRepository tasks;
    private final TaskQueuePort queue;
    private final StageLogRepository stageLogs;
    private final long pendingGraceSeconds;

    public TaskCompensationJob(VideoTaskRepository tasks,
                               TaskQueuePort queue,
                               StageLogRepository stageLogs,
                               @Value("${video.mq.pending-grace-seconds:60}") long pendingGraceSeconds) {
        this.tasks = tasks;
        this.queue = queue;
        this.stageLogs = stageLogs;
        this.pendingGraceSeconds = pendingGraceSeconds;
    }

    @Scheduled(fixedDelayString = "${video.mq.compensation-interval-ms:30000}")
    public void resendPending() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(pendingGraceSeconds);
        List<VideoTaskEntity> stale = tasks.findStale(
                TaskStatus.PENDING, before, PageRequest.of(0, 100));

        for (VideoTaskEntity task : stale) {
            try {
                int nextAttempt = Math.max(1, stageLogs.findMaxAttempt(task.getId()) + 1);
                queue.enqueue(new TaskMessage(task.getId(), nextAttempt));
                log.info("补偿投递任务 {}", task.getId());
            } catch (Exception e) {
                // 保持 PENDING，下一轮继续补
                log.error("补偿投递任务 {} 失败", task.getId(), e);
            }
        }
    }
}
