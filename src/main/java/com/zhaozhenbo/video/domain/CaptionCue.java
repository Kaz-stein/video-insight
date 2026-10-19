package com.zhaozhenbo.video.domain;

/**
 * 一条字幕（一条 cue），是整条流水线里最小的“原文证据单元”。
 *
 * 为什么单独建一个类型，而不是直接用 String：
 *   - 后面每一条笔记要点都要回指到具体时间区间，时间必须跟着文本一起走；
 *   - index 是“第几条字幕”，证据校验时用它定位原文，比反复比较文本更可靠；
 *   - record 天然不可变，跨线程传给消费线程时不需要防御性拷贝。
 */
public record CaptionCue(int index, long startMs, long endMs, String text) {

    public CaptionCue {
        if (index < 0) {
            throw new IllegalArgumentException("字幕序号不能为负: " + index);
        }
        if (startMs < 0 || endMs < 0) {
            throw new IllegalArgumentException("时间戳不能为负: " + startMs + " -> " + endMs);
        }
        if (endMs < startMs) {
            throw new IllegalArgumentException("结束时间早于开始时间: " + startMs + " -> " + endMs);
        }
        text = text == null ? "" : text.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("字幕文本不能为空");
        }
    }

    public long durationMs() {
        return endMs - startMs;
    }
}
