package com.zhaozhenbo.video.pipeline.agent;

import java.util.List;

/**
 * 成稿结果（还没落库）。T6 会把它拆成 note + note_point 两张表。
 */
public record NoteDraft(
        String title,
        String summary,
        String markdown,
        List<OutlineSection> outline,
        List<VerifiedPoint> points,
        String model,
        String promptVersion
) {
    public NoteDraft {
        title = title == null ? "" : title.strip();
        summary = summary == null ? "" : summary.strip();
        markdown = markdown == null ? "" : markdown;
        outline = outline == null ? List.of() : List.copyOf(outline);
        points = points == null ? List.of() : List.copyOf(points);
    }

    /** 质量门槛：可回溯率 = GROUNDED / 全部要点 */
    public double groundedRatio() {
        if (points.isEmpty()) {
            return 0;
        }
        long grounded = points.stream().filter(VerifiedPoint::grounded).count();
        return (double) grounded / points.size();
    }

    /** 低于 40% 不落库，任务判失败让用户重试，而不是产出一篇“看起来像笔记”的垃圾。 */
    public boolean passesQualityGate() {
        return groundedRatio() >= 0.4 && !markdown.isBlank();
    }
}
