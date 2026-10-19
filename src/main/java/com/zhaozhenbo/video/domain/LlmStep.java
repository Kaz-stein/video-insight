package com.zhaozhenbo.video.domain;

/** Agent 四步。步骤名会进 prompt 版本号和日志，不要随便改名。 */
public enum LlmStep {
    PLANNER,
    EXTRACTOR,
    CRITIC,
    COMPOSER
}
