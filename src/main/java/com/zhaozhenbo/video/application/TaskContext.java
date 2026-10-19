package com.zhaozhenbo.video.application;

/**
 * 阶段处理器需要的任务快照。
 *
 * 用 record 传值而不是把 JPA 实体传下去：
 *   - 实体带 Session 生命周期，传到异步线程容易踩懒加载/游离态；
 *   - record 不可变，处理器不能偷偷改任务字段，状态只能由 PipelineService 推进。
 */
public record TaskContext(
        long taskId,
        long userId,
        String fingerprint,
        String sourceType,
        String sourceRef,
        String title,
        Long durationMs
) {
}
