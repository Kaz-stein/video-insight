package com.zhaozhenbo.video.adapter.mq;

import com.zhaozhenbo.video.adapter.persistence.VideoTaskEntity;
import com.zhaozhenbo.video.adapter.persistence.VideoTaskRepository;
import com.zhaozhenbo.video.application.TaskContext;
import com.zhaozhenbo.video.domain.Stage;
import com.zhaozhenbo.video.domain.TaskMessage;
import com.zhaozhenbo.video.domain.TaskStatus;
import com.zhaozhenbo.video.port.StageHandler;
import com.zhaozhenbo.video.port.TaskQueuePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可选的“真 broker”冒烟测试：默认不跑，避免 CI 依赖本机 RocketMQ。
 *
 * 手动运行：
 *   $env:MQ_SMOKE='true'
 *   mvn -Dtest=RocketMqSmokeTest test
 *
 * 它验证的是内存队列测不到的东西：JSON 序列化、Topic 自动创建、
 * 消费者组订阅、broker 投递、消费端抢占。
 */
@SpringBootTest(properties = {
        "video.mq.enabled=true",
        "video.mq.topic=video-task-smoke",
        "video.mq.producer-group=video-task-smoke-producer",
        "video.mq.consumer-group=video-task-smoke-consumer",
        "video.mq.compensation-interval-ms=3600000"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MQ_SMOKE", matches = "true")
@Import(RocketMqSmokeTest.HandlerConfig.class)
class RocketMqSmokeTest {

    @Autowired
    private TaskQueuePort queue;

    @Autowired
    private VideoTaskRepository tasks;

    @Test
    @DisplayName("真实 RocketMQ：发送一条消息，任务在 60 秒内跑完六个阶段")
    void messageDrivesTaskToDone() throws Exception {
        VideoTaskEntity task = new VideoTaskEntity();
        task.setUserId(9);
        task.setSourceType("FILE");
        task.setSourceRef("D:/data/smoke.mp4");
        task.setFingerprint("c".repeat(64));
        task.setParamsHash("d".repeat(64));
        task.setStatus(TaskStatus.PENDING);
        task = tasks.save(task);

        queue.enqueue(new TaskMessage(task.getId(), 1));

        // 新 Topic 由 broker 自动创建后，还要等 broker 心跳把路由推到 NameServer，
        // 首次运行可能接近 30s；这里给 60s，避免把环境延迟误判成代码 bug。
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        TaskStatus status = TaskStatus.PENDING;
        while (Instant.now().isBefore(deadline)) {
            status = tasks.findById(task.getId()).orElseThrow().getStatus();
            if (status == TaskStatus.DONE || status == TaskStatus.FAILED) {
                break;
            }
            Thread.sleep(200);
        }

        assertThat(status).isEqualTo(TaskStatus.DONE);
        assertThat(tasks.findById(task.getId()).orElseThrow().getStage()).isEqualTo(Stage.COMPOSING);
    }

    @TestConfiguration
    static class HandlerConfig {

        @Bean
        StageHandler fetchHandler() {
            return noop(Stage.FETCHING);
        }

        @Bean
        StageHandler segmentHandler() {
            return noop(Stage.SEGMENTING);
        }

        @Bean
        StageHandler planHandler() {
            return noop(Stage.PLANNING);
        }

        @Bean
        StageHandler extractHandler() {
            return noop(Stage.EXTRACTING);
        }

        @Bean
        StageHandler verifyHandler() {
            return noop(Stage.VERIFYING);
        }

        @Bean
        StageHandler composeHandler() {
            return noop(Stage.COMPOSING);
        }

        private static StageHandler noop(Stage stage) {
            return new StageHandler() {
                @Override
                public Stage stage() {
                    return stage;
                }

                @Override
                public void execute(TaskContext context) {
                    // 冒烟测试只关心消息是否被正确投递和执行
                }
            };
        }
    }
}
