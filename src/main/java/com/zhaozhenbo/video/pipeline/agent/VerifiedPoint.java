package com.zhaozhenbo.video.pipeline.agent;

/**
 * 经过后端校验后的要点。overlap 是 3-gram 重合度，reason 记录为什么被降级。
 */
public record VerifiedPoint(
        String text,
        String quote,
        String section,
        long startMs,
        long endMs,
        double overlap,
        PointStatus status,
        String reason
) {
    public VerifiedPoint {
        text = text == null ? "" : text.strip();
        quote = quote == null ? "" : quote.strip();
        section = section == null ? "" : section.strip();
        reason = reason == null ? "" : reason.strip();
    }

    public boolean grounded() {
        return status == PointStatus.GROUNDED;
    }
}
