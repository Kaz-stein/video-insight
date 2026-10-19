package com.zhaozhenbo.video.domain;

/**
 * 一次 LLM 调用的完整描述。
 *
 * input 不是给真实模型看的（真实模型只看 prompt），而是给 FakeLlm / 日志 / 评测用的
 * 结构化输入。这样测试不需要去解析 prompt 字符串，prompt 改格式不会把测试改崩。
 */
public record LlmCall(
        LlmStep step,
        String promptVersion,
        String systemPrompt,
        String userPrompt,
        Object input,
        double temperature,
        int maxTokens
) {
    public LlmCall {
        if (step == null || promptVersion == null || userPrompt == null) {
            throw new IllegalArgumentException("LLM 调用缺少必要字段");
        }
    }
}
