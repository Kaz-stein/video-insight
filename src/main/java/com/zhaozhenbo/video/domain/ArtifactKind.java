package com.zhaozhenbo.video.domain;

/**
 * 阶段产物的逻辑类型。
 *
 * stageKey 带 pipeline 版本：改切段算法 / prompt 后 bump 版本，
 * 旧产物不会被新代码误命中。
 */
public enum ArtifactKind {
    CAPTION,
    SEGMENT,
    PLAN,
    EXTRACT,
    VERIFY,
    NOTE;

    public static final String PIPELINE_VERSION = "p1";

    public String stageKey() {
        return name() + "@" + PIPELINE_VERSION;
    }
}
