package com.zhaozhenbo.video.port;

/** 模型不可用、超时、返回非 JSON 等统一异常。 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
