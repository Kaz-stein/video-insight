package com.zhaozhenbo.video.api.dto;

/**
 * 评测指标：
 *   citationRecall  = GROUNDED / 全部要点
 *   unsupportedRate = REJECTED / 全部要点
 *   avgDurationMs   = DONE 任务的平均端到端耗时
 */
public record EvalResponse(
        double citationRecall,
        double unsupportedRate,
        long avgDurationMs,
        int noteCount,
        int pointCount
) {
}
