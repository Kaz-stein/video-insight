package com.zhaozhenbo.video.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机单测：不启动 Spring、不连数据库，整个类跑完是毫秒级。
 * 这就是"领域对象不依赖框架"的直接收益。
 */
class TaskStateTest {

    @Test
    @DisplayName("正常路径：依次完成六个阶段后变为 DONE")
    void happyPath() {
        TaskState state = TaskState.initial().start();
        assertThat(state.status()).isEqualTo(TaskStatus.RUNNING);

        for (Stage stage : Stage.values()) {
            state = state.completeStage(stage);
        }

        assertThat(state.status()).isEqualTo(TaskStatus.DONE);
        assertThat(state.stage()).isEqualTo(Stage.COMPOSING);
    }

    @Test
    @DisplayName("跳阶段：PENDING 直接完成 PLANNING 应失败")
    void skipStage() {
        TaskState running = TaskState.initial().start();
        assertThatThrownBy(() -> running.completeStage(Stage.PLANNING))
                .isInstanceOf(IllegalTaskTransitionException.class)
                .hasMessageContaining("阶段顺序错误");
    }

    @Test
    @DisplayName("回退阶段：完成后又 complete 上一个阶段应失败")
    void regressStage() {
        TaskState state = TaskState.initial().start().completeStage(Stage.FETCHING);
        assertThatThrownBy(() -> state.completeStage(Stage.FETCHING))
                .isInstanceOf(IllegalTaskTransitionException.class);
    }

    @Test
    @DisplayName("终态：DONE 之后不能再 fail 或 retry")
    void terminalState() {
        TaskState done = TaskState.initial().start();
        for (Stage stage : Stage.values()) {
            done = done.completeStage(stage);
        }
        assertThat(done.status()).isEqualTo(TaskStatus.DONE);

        TaskState finalDone = done;
        assertThatThrownBy(finalDone::fail)
                .isInstanceOf(IllegalTaskTransitionException.class);
        assertThatThrownBy(finalDone::retry)
                .isInstanceOf(IllegalTaskTransitionException.class);
    }

    @Test
    @DisplayName("未启动不能推进阶段")
    void cannotAdvanceBeforeStart() {
        TaskState pending = TaskState.initial();
        assertThatThrownBy(() -> pending.completeStage(Stage.FETCHING))
                .isInstanceOf(IllegalTaskTransitionException.class)
                .hasMessageContaining("只有 RUNNING");
    }

    @Test
    @DisplayName("失败重试：阶段不回退，从下一个阶段继续（checkpoint 的语义）")
    void retryKeepsCheckpoint() {
        TaskState state = TaskState.initial()
                .start()
                .completeStage(Stage.FETCHING)
                .completeStage(Stage.SEGMENTING)
                .fail();

        assertThat(state.status()).isEqualTo(TaskStatus.FAILED);

        TaskState retried = state.retry().start();
        assertThat(retried.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(retried.nextStage()).isEqualTo(Stage.PLANNING);
        assertThat(retried.stage()).isEqualTo(Stage.SEGMENTING);
    }

    @Test
    @DisplayName("只有 FAILED 能 retry，RUNNING 不行")
    void cannotRetryRunning() {
        TaskState running = TaskState.initial().start();
        assertThatThrownBy(running::retry)
                .isInstanceOf(IllegalTaskTransitionException.class);
    }
}
