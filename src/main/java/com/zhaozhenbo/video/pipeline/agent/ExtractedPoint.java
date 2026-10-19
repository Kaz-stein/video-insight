package com.zhaozhenbo.video.pipeline.agent;

/**
 * Extractor 的原始产出。此时还没有经过证据校验，不能直接展示给用户。
 */
public record ExtractedPoint(
        String text,
        String quote,
        long startMs,
        long endMs,
        String section
) {
    public ExtractedPoint {
        text = text == null ? "" : text.strip();
        quote = quote == null ? "" : quote.strip();
        section = section == null ? "" : section.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("要点正文不能为空");
        }
        if (endMs < startMs) {
            throw new IllegalArgumentException("要点时间区间非法: " + startMs + " -> " + endMs);
        }
    }
}
