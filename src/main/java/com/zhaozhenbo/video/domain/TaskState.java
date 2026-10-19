package com.zhaozhenbo.video.domain;

import java.util.Objects;

/**
 * 任务状态机（不可变）。
 *
 * 设计要点：
 *   1) 状态迁移写成"方法"，每个方法内部校验前置状态，非法就抛异常 ——
 *      而不是让调用方到处写 if (status == DONE) return;
 *   2) 对象不可变：每次迁移返回新实例，避免多个线程共享同一个可变对象
 *   3) 不依赖 Spring / JPA / 数据库：纯 Java，单测毫秒级（这是分层带来的直接好处）
 *
 * 迁移规则：
 *   PENDING  --start-->   RUNNING
 *   RUNNING  --completeStage(按顺序)--> RUNNING（直到 COMPOSING 后变 DONE）
 *   RUNNING  --fail-->    FAILED
 *   FAILED   --retry-->   PENDING（阶段不回退，因此天然从断点续跑）
 *   DONE     --任何操作--> 抛异常（终态）
 */
public final class TaskState {

    private final TaskStatus status;
    /** 最后成功完成的阶段；null 表示一个阶段都还没完成 */
    private final Stage stage;

    private TaskState(TaskStatus status, Stage stage) {
        this.status = status;
        this.stage = stage;
    }

    public static TaskState initial() {
        return new TaskState(TaskStatus.PENDING, null);
    }

    public static TaskState of(TaskStatus status, Stage stage) {
        return new TaskState(Objects.requireNonNull(status), stage);
    }

    public TaskStatus status() {
        return status;
    }

    public Stage stage() {
        return stage;
    }

    /** 下一次调度应该执行哪个阶段 */
    public Stage nextStage() {
        return stage == null ? Stage.FETCHING : stage.next();
    }

    /** PENDING → RUNNING */
    public TaskState start() {
        require(status == TaskStatus.PENDING,
                "只有 PENDING 可以开始执行，当前是 " + status);
        return new TaskState(TaskStatus.RUNNING, stage);
    }

    /**
     * 完成一个阶段。
     * 必须严格按 Stage 的顺序来：跳阶段、回退、重复完成同一阶段都视为错误。
     * 重复执行是允许的（重试），但那是"重新执行"，不应该再次 completeStage 同一个阶段。
     */
    public TaskState completeStage(Stage finished) {
        require(status == TaskStatus.RUNNING,
                "只有 RUNNING 可以推进阶段，当前是 " + status);

        Stage expected = nextStage();
        require(expected != null,
                "所有阶段都已完成，任务应当已经是 DONE");
        require(finished == expected,
                "阶段顺序错误：期望 " + expected + "，实际 " + finished);

        Stage after = finished.next();
        // 最后一个阶段完成 → 任务完成；否则继续 RUNNING
        return new TaskState(after == null ? TaskStatus.DONE : TaskStatus.RUNNING, finished);
    }

    /** RUNNING → FAILED */
    public TaskState fail() {
        require(status == TaskStatus.RUNNING,
                "只有 RUNNING 可以置为失败，当前是 " + status);
        return new TaskState(TaskStatus.FAILED, stage);
    }

    /** FAILED → PENDING（阶段保留，因此重试从 nextStage() 继续） */
    public TaskState retry() {
        require(status == TaskStatus.FAILED,
                "只有 FAILED 可以重试，当前是 " + status);
        return new TaskState(TaskStatus.PENDING, stage);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalTaskTransitionException(message);
    }

    @Override
    public String toString() {
        return status + (stage == null ? "" : "@" + stage);
    }
}
