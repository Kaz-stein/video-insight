package com.zhaozhenbo.video.api;

public class TaskNotFoundException extends RuntimeException {
    public TaskNotFoundException(long id) {
        super("任务不存在: " + id);
    }
}
