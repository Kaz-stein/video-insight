package com.zhaozhenbo.video.application;

import com.zhaozhenbo.video.adapter.mq.InMemoryTaskQueueAdapter;
import com.zhaozhenbo.video.adapter.persistence.StageLogEntity;
import com.zhaozhenbo.video.adapter.persistence.StageLogRepository;
import com.zhaozhenbo.video.adapter.persistence.VideoTaskEntity;
import com.zhaozhenbo.video.adapter.persistence.VideoTaskRepository;
import com.zhaozhenbo.video.domain.RetryDelay;
import com.zhaozhenbo.video.domain.Stage;
import com.zhaozhenbo.video.domain.TaskMessage;
import com.zhaozhenbo.video.domain.TaskStatus;
import com.zhaozhenbo.video.port.StageHandler;
import com.zhaozhenbo.video.port.TaskQueuePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4 验收：
 *   1) 同一消息投递 3 次只执行一次
 *   2) 第 3 阶段失败后重试，不重跑前两阶段
 *   3) 1s/5s/30s 三档重试，第 4 次失败进死信
 *   4) 发送补偿能把超时的 PENDING 重新投递
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(TaskPipelineServiceTest.HandlerConfig.class)
class TaskPipelineServiceTest {

    @Autowired
    private TaskPipelineService pipeline;

    @Autowired
    private VideoTaskRepository tasks;

    @Autowired
    private StageLogRepository stageLogs;

    @Autowired
    private TaskQueuePort queue;

    @Autowired
    private TaskCompensationJob compensationJob;

    @Autowired
    private StageProbe probe;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private InMemoryTaskQueueAdapter memoryQueue() {
        return (InMemoryTaskQueueAdapter) queue;
    }

    @BeforeEach
    void resetProbe() {
        probe.reset();
        // 测试事务回滚不到内存对象，手动清空
        memoryQueue().clear();
    }

    @Test
    @DisplayName("重复投递 3 次：六个阶段各执行一次")
    void duplicateDeliveryRunsOnce() {
        VideoTaskEntity task = newTask();
        TaskMessage message = new TaskMessage(task.getId(), 1);

        pipeline.handle(message);
        pipeline.handle(message);
        pipeline.handle(message);

        assertThat(probe.calls(Stage.FETCHING)).isEqualTo(1);
        assertThat(probe.calls(Stage.COMPOSING)).isEqualTo(1);
        assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(stageLogs.findByTaskIdOrderByIdAsc(task.getId()))
                .hasSize(Stage.values().length)
                .allSatisfy(log -> assertThat(log.getStatus()).isEqualTo("OK"));
    }

    @Test
    @DisplayName("PLANNING 失败后重试：从 PLANNING 继续，FETCHING/SEGMENTING 不重跑")
    void retryResumesFromCheckpoint() {
        VideoTaskEntity task = newTask();
        probe.failNext(Stage.PLANNING, 1);

        pipeline.handle(new TaskMessage(task.getId(), 1));

        VideoTaskEntity failed = tasks.findById(task.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(failed.getStage()).isEqualTo(Stage.SEGMENTING);
        assertThat(probe.calls(Stage.FETCHING)).isEqualTo(1);
        assertThat(probe.calls(Stage.SEGMENTING)).isEqualTo(1);
        assertThat(probe.calls(Stage.PLANNING)).isEqualTo(1);

        pipeline.handle(new TaskMessage(task.getId(), 2));

        assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(probe.calls(Stage.FETCHING)).isEqualTo(1);
        assertThat(probe.calls(Stage.SEGMENTING)).isEqualTo(1);
        assertThat(probe.calls(Stage.PLANNING)).isEqualTo(2);
        assertThat(probe.calls(Stage.EXTRACTING)).isEqualTo(1);
    }

    @Test
    @DisplayName("连续失败：1s/5s/30s 重试，第 4 次失败进死信")
    void retryThenDeadLetter() {
        VideoTaskEntity task = newTask();
        probe.failNext(Stage.PLANNING, 10);

        for (int attempt = 1; attempt <= TaskPipelineService.MAX_ATTEMPTS; attempt++) {
            pipeline.handle(new TaskMessage(task.getId(), attempt));
        }

        assertThat(memoryQueue().retries())
                .extracting(InMemoryTaskQueueAdapter.RetryRecord::delay)
                .containsExactly(RetryDelay.ONE_SECOND, RetryDelay.FIVE_SECONDS, RetryDelay.THIRTY_SECONDS);
        assertThat(memoryQueue().deadLetters()).hasSize(1);
        assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.FAILED);

        List<StageLogEntity> planningLogs = stageLogs.findByTaskIdOrderByIdAsc(task.getId()).stream()
                .filter(log -> log.getStage() == Stage.PLANNING)
                .toList();
        assertThat(planningLogs).hasSize(TaskPipelineService.MAX_ATTEMPTS);
        assertThat(planningLogs).extracting(StageLogEntity::getAttempt).containsExactly(1, 2, 3, 4);
        assertThat(planningLogs).allSatisfy(log -> assertThat(log.getStatus()).isEqualTo("FAILED"));
    }

    @Test
    @DisplayName("补偿任务：超过宽限期的 PENDING 会被重新投递")
    void compensationResendsPendingTask() {
        VideoTaskEntity task = newTask();
        jdbcTemplate.update(
                "update video_task set updated_at = ? where id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(5)),
                task.getId());

        compensationJob.resendPending();

        assertThat(memoryQueue().size()).isGreaterThanOrEqualTo(1);
        TaskMessage resent = memoryQueue().poll();
        assertThat(resent).isNotNull();
        assertThat(resent.taskId()).isEqualTo(task.getId());
        assertThat(resent.attempt()).isEqualTo(1);
    }

    private VideoTaskEntity newTask() {
        VideoTaskEntity task = new VideoTaskEntity();
        task.setUserId(1);
        task.setSourceType("FILE");
        task.setSourceRef("D:/data/demo.mp4");
        task.setFingerprint("a".repeat(64));
        task.setParamsHash("b".repeat(64));
        task.setStatus(TaskStatus.PENDING);
        return tasks.save(task);
    }

    @TestConfiguration
    static class HandlerConfig {

        @Bean
        StageProbe stageProbe() {
            return new StageProbe();
        }

        @Bean
        StageHandler fetchingHandler(StageProbe probe) {
            return handler(Stage.FETCHING, probe);
        }

        @Bean
        StageHandler segmentingHandler(StageProbe probe) {
            return handler(Stage.SEGMENTING, probe);
        }

        @Bean
        StageHandler planningHandler(StageProbe probe) {
            return handler(Stage.PLANNING, probe);
        }

        @Bean
        StageHandler extractingHandler(StageProbe probe) {
            return handler(Stage.EXTRACTING, probe);
        }

        @Bean
        StageHandler verifyingHandler(StageProbe probe) {
            return handler(Stage.VERIFYING, probe);
        }

        @Bean
        StageHandler composingHandler(StageProbe probe) {
            return handler(Stage.COMPOSING, probe);
        }

        private static StageHandler handler(Stage stage, StageProbe probe) {
            return new StageHandler() {
                @Override
                public Stage stage() {
                    return stage;
                }

                @Override
                public void execute(TaskContext context) {
                    probe.record(stage);
                    if (probe.consumeFailure(stage)) {
                        throw new IllegalStateException(stage + " 模拟失败");
                    }
                }
            };
        }
    }

    static class StageProbe {
        private final Map<Stage, AtomicInteger> calls = new EnumMap<>(Stage.class);
        private final Map<Stage, AtomicInteger> failures = new EnumMap<>(Stage.class);

        StageProbe() {
            for (Stage stage : Stage.values()) {
                calls.put(stage, new AtomicInteger());
                failures.put(stage, new AtomicInteger());
            }
        }

        void reset() {
            calls.values().forEach(counter -> counter.set(0));
            failures.values().forEach(counter -> counter.set(0));
        }

        void record(Stage stage) {
            calls.get(stage).incrementAndGet();
        }

        void failNext(Stage stage, int times) {
            failures.get(stage).set(times);
        }

        boolean consumeFailure(Stage stage) {
            AtomicInteger remaining = failures.get(stage);
            while (true) {
                int current = remaining.get();
                if (current <= 0) {
                    return false;
                }
                if (remaining.compareAndSet(current, current - 1)) {
                    return true;
                }
            }
        }

        int calls(Stage stage) {
            return calls.get(stage).get();
        }
    }
}
