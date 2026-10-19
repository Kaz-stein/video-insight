package com.zhaozhenbo.video.pipeline.agent;

/** Composer 的结构化输出：摘要单独存，Markdown 是展示层。 */
public record ComposerOutput(String summary, String markdown) {
}
