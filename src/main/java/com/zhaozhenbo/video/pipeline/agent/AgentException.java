package com.zhaozhenbo.video.pipeline.agent;

/** Agent 输出无法修复时的异常（例如 Extractor 全部章节都失败）。 */
public class AgentException extends RuntimeException {

    public AgentException(String message) {
        super(message);
    }

    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
