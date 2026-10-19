package com.zhaozhenbo.video.pipeline.agent;

/** 笔记质量不达标（例如可回溯率 < 40%），不落库、让任务失败重试。 */
public class QualityGateException extends RuntimeException {

    public QualityGateException(String message) {
        super(message);
    }
}
