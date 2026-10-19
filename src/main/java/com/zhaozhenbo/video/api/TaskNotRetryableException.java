package com.zhaozhenbo.video.api;

public class TaskNotRetryableException extends RuntimeException {
    public TaskNotRetryableException(String message) {
        super(message);
    }
}
