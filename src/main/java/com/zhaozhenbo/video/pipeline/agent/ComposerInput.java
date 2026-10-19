package com.zhaozhenbo.video.pipeline.agent;

import java.util.List;

/**
 * 成稿输入。
 *
 * sections 是按章节切好的原文，缺了它模型只能对着几条一句话要点干瞪眼，
 * 写出来的"总结"自然是把要点重新排一遍 —— 这也是之前笔记偏薄的根因。
 */
public record ComposerInput(String title,
                            Plan plan,
                            List<SectionText> sections,
                            List<VerifiedPoint> points) {
    public ComposerInput {
        sections = sections == null ? List.of() : List.copyOf(sections);
        points = List.copyOf(points);
    }

    /** 兼容旧调用方：没有原文时退化成只给要点。 */
    public ComposerInput(String title, Plan plan, List<VerifiedPoint> points) {
        this(title, plan, List.of(), points);
    }

    /** 一个章节的原文（把该章节所有语义段拼起来）。 */
    public record SectionText(String title, String text) {
    }
}
