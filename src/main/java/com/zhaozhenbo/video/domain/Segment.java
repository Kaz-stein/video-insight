package com.zhaozhenbo.video.domain;

/**
 * 切段结果：一段连续内容 + 它在原片中的时间区间。
 *
 * firstCue / lastCue 保存的是原始字幕序号（闭区间），这样：
 *   - 模型只拿到“第 12~18 条字幕”，引用时可以精确回到原文；
 *   - 校验阶段不必把整段文本再切一遍，直接按序号取 cue 做重合度计算。
 */
public record Segment(
        int index,
        long startMs,
        long endMs,
        String text,
        int firstCue,
        int lastCue
) {

    public Segment {
        if (index < 0) {
            throw new IllegalArgumentException("段落序号不能为负: " + index);
        }
        if (startMs < 0 || endMs < startMs) {
            throw new IllegalArgumentException("非法时间区间: " + startMs + " -> " + endMs);
        }
        if (firstCue < 0 || lastCue < firstCue) {
            throw new IllegalArgumentException("非法字幕区间: " + firstCue + " -> " + lastCue);
        }
        text = text == null ? "" : text.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("段落文本不能为空");
        }
    }

    public long durationMs() {
        return endMs - startMs;
    }
}
