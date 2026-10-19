package com.zhaozhenbo.video.domain;

/**
 * 任务状态（只有四个，不设 RETRYING）。
 *
 * 为什么不要 RETRYING：重试只是"回到 PENDING 等下一次调度"，
 * 多一个状态就要多一套判断，而且和 PENDING 的语义几乎重合。
 * 重试次数记在 stage_log 里（attempt），不该混进任务状态。
 */
public enum TaskStatus {
    PENDING,  // 待调度
    RUNNING,  // 执行中
    DONE,     // 完成（终态，不可再变）
    FAILED    // 失败（可重试回 PENDING）
}
