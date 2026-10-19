package com.zhaozhenbo.video.pipeline.agent;

/**
 * 证据状态，三档而不是二值：
 *   GROUNDED  = 原文里确实有，且语义校验通过
 *   INFERRED  = 有原文影子，但重合度/语义不足，正文必须标注“模型推断”
 *   REJECTED  = 找不到依据或时间区间对不上，不能进正文
 */
public enum PointStatus {
    GROUNDED,
    INFERRED,
    REJECTED
}
