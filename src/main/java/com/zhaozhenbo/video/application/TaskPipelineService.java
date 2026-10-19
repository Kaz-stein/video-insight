package com.zhaozhenbo.video.application;

import com.zhaozhenbo.video.adapter.persistence.StageLogEntity;
import com.zhaozhenbo.video.adapter.persistence.StageLogRepository;
import com.zhaozhenbo.video.adapter.persistence.VideoTaskEntity;
import com.zhaozhenbo.video.adapter.persistence.VideoTaskRepository;
import com.zhaozhenbo.video.domain.RetryDelay;
import com.zhaozhenbo.video.domain.Stage;
import com.zhaozhenbo.video.domain.TaskMessage;
import com.zhaozhenbo.video.domain.TaskState;
import com.zhaozhenbo.video.domain.TaskStatus;
import com.zhaozhenbo.video.port.StageHandler;
import com.zhaozhenbo.video.port.TaskQueuePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 流水线执行器：消费者只调用 handle，其余都在这里统一处理。
 *
 * 职责边界：
 *   1) 抢占执行权（数据库条件更新）—— 解决重复投递
 *   2) 从 task.stage 的下一阶段继续 —— 解决“重试不重跑已完成阶段”
 *   3) 每阶段写 stage_log —— 解决可观测性
 *   4) 失败后延迟重试 / 超限进死信 —— 解决瞬时故障
 *
 * 注意：方法故意不加 @Transactional。
 * 如果把整个任务包在一个事务里，第 3 阶段失败会把前两阶段的日志一起回滚，
 * 排障时什么都看不到。每个阶段独立提交，才能留下“做到哪儿”的证据。
 */
@Service
public class TaskPipelineService {

    private static final Logger log = LoggerFactory.getLogger(TaskPipelineService.class);

    /** 首次 + 3 次重试；第 4 次仍失败就进死信 */
    static final int MAX_ATTEMPTS = 4;

    private final VideoTaskRepository tasks;
    private final StageLogRepository stageLogs;
    private final TaskQueuePort queue;
    private final Map<Stage, StageHandler> handlers;

    public TaskPipelineService(VideoTaskRepository tasks,
                               StageLogRepository stageLogs,
                               TaskQueuePort queue,
                               List<StageHandler> handlers) {
        this.tasks = tasks;
        this.stageLogs = stageLogs;
        this.queue = queue;
        this.handlers = new EnumMap<>(Stage.class);
        for (StageHandler handler : handlers) {
            StageHandler previous = this.handlers.put(handler.stage(), handler);
            if (previous != null) {
                throw new IllegalStateException("阶段处理器重复注册: " + handler.stage());
            }
        }
    }

    public void handle(TaskMessage message) {
        // 首次投递只能抢 PENDING；重试投递只能抢 FAILED。
        // 这样同一条消息被 broker 重复投递 3 次，也只有一次能进入执行。
        int claimed;
        if (message.attempt() == 1) {
            claimed = tasks.claim(message.taskId(), TaskStatus.PENDING, TaskStatus.RUNNING, LocalDateTime.now());
        } else {
            // 自动重试时状态是 FAILED；手动重试接口会先把状态置为 PENDING。
            // 两种都要能抢到，否则手动重试消息会被静默跳过。
            claimed = tasks.claim(message.taskId(), TaskStatus.FAILED, TaskStatus.RUNNING, LocalDateTime.now());
            if (claimed == 0) {
                claimed = tasks.claim(message.taskId(), TaskStatus.PENDING, TaskStatus.RUNNING, LocalDateTime.now());
            }
        }
        if (claimed == 0) {
            log.info("跳过重复/过期消息 taskId={} attempt={}", message.taskId(), message.attempt());
            return;
        }

        VideoTaskEntity task = tasks.findById(message.taskId()).orElse(null);
        if (task == null) {
            log.warn("任务已不存在，忽略消息 taskId={}", message.taskId());
            return;
        }

        try {
            runStages(task, message);
        } catch (StageFailedException e) {
            fail(task, message, e);
        } catch (Exception e) {
            fail(task, message, new StageFailedException(null, e));
        }
    }

    private void runStages(VideoTaskEntity task, TaskMessage message) {
        // 关键：从“最后成功阶段”的下一阶段开始，而不是从 FETCHING 重来
        TaskState state = TaskState.of(TaskStatus.RUNNING, task.getStage());
        TaskContext context = toContext(task);

        while (true) {
            Stage stage = state.nextStage();
            if (stage == null) {
                break; // 所有阶段完成，状态已经是 DONE
            }

            StageHandler handler = handlers.get(stage);
            if (handler == null) {
                throw new StageFailedException(stage,
                        new IllegalStateException("阶段处理器未注册: " + stage));
            }

            StageLogEntity stageLog = startLog(task.getId(), stage, message.attempt());
            long startedAt = System.nanoTime();
            try {
                handler.execute(context);
            } catch (Exception e) {
                finishLog(stageLog, "FAILED", startedAt, e);
                throw new StageFailedException(stage, e);
            }
            finishLog(stageLog, "OK", startedAt, null);

            state = state.completeStage(stage);
            tasks.updateProgress(task.getId(), state.status(), state.stage(), LocalDateTime.now());
            log.info("任务 {} 阶段 {} 完成，当前状态 {}", task.getId(), stage, state);
        }
    }

    private void fail(VideoTaskEntity task, TaskMessage message, StageFailedException failure) {
        String error = failure.describe();
        tasks.markFailed(task.getId(), TaskStatus.FAILED, error, LocalDateTime.now());

        int failedCount = failure.stage == null
                ? message.attempt()
                : (int) stageLogs.countByTaskIdAndStageAndStatus(task.getId(), failure.stage, "FAILED");
        if (failedCount >= MAX_ATTEMPTS) {
            queue.sendToDeadLetter(message, error);
            log.error("任务 {} 阶段 {} 累计失败 {} 次，进入死信: {}",
                    task.getId(), failure.stage, failedCount, error, failure.getCause());
            return;
        }

        RetryDelay delay = RetryDelay.afterFailure(Math.max(1, failedCount));
        try {
            queue.enqueueRetry(message.nextAttempt(), delay);
            log.warn("任务 {} 第 {} 次尝试失败，{} 后重试: {}",
                    task.getId(), message.attempt(), delay, error);
        } catch (Exception sendError) {
            // 任务保持 FAILED，至少不会假装成功；补偿/人工重试仍可介入
            log.error("任务 {} 的重试消息发送失败，任务保持 FAILED", task.getId(), sendError);
        }
    }

    private StageLogEntity startLog(long taskId, Stage stage, int attempt) {
        StageLogEntity entity = new StageLogEntity();
        entity.setTaskId(taskId);
        entity.setStage(stage);
        entity.setStatus("RUNNING");
        entity.setStartedAt(LocalDateTime.now());
        entity.setAttempt(attempt);
        return stageLogs.save(entity);
    }

    private void finishLog(StageLogEntity entity, String status, long startedAt, Exception error) {
        entity.setStatus(status);
        entity.setEndedAt(LocalDateTime.now());
        entity.setDurationMs((System.nanoTime() - startedAt) / 1_000_000);
        if (error != null) {
            entity.setError(truncate(error.getClass().getSimpleName() + ": " + error.getMessage()));
        }
        stageLogs.save(entity);
    }

    private static TaskContext toContext(VideoTaskEntity task) {
        return new TaskContext(
                task.getId(),
                task.getUserId(),
                task.getFingerprint(),
                task.getSourceType(),
                task.getSourceRef(),
                task.getTitle(),
                task.getDurationMs()
        );
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }

    /** 带阶段信息的失败，方便定位“到底哪一步挂了” */
    static final class StageFailedException extends RuntimeException {
        private final Stage stage;

        StageFailedException(Stage stage, Throwable cause) {
            super(cause);
            this.stage = stage;
        }

        String describe() {
            Throwable root = getCause() == null ? this : getCause();
            String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
            return truncate((stage == null ? "" : stage + ": ") + message);
        }
    }
}
