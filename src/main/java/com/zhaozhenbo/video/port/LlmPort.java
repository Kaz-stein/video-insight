package com.zhaozhenbo.video.port;

import com.zhaozhenbo.video.domain.LlmCall;

/**
 * 文本模型端口。返回原始 JSON 字符串，由调用方按契约反序列化并校验。
 *
 * 不在这里直接把 JSON 转成对象，是为了让“模型输出不合法”成为显式的失败，
 * 而不是被框架悄悄吞掉或返回 null。
 */
public interface LlmPort {

    String complete(LlmCall call) throws LlmException;
}
