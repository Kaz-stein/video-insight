package com.zhaozhenbo.video.api;

public class NoteNotReadyException extends RuntimeException {
    public NoteNotReadyException(long taskId) {
        super("任务 " + taskId + " 的笔记尚未生成");
    }
}
