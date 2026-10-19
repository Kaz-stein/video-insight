package com.zhaozhenbo.video.port;

/** 队列不可用（broker 连不上、发送超时）。调用方可以选择重试或补偿。 */
public class TaskQueueException extends RuntimeException {

    public TaskQueueException(String message, Throwable cause) {
        super(message, cause);
    }
}
