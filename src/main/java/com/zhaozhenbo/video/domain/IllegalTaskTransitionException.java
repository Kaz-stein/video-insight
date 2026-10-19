package com.zhaozhenbo.video.domain;

/** 非法的状态迁移。继承 RuntimeException：这是调用方的编程错误，不该逼上层写 try-catch */
public class IllegalTaskTransitionException extends RuntimeException {
    public IllegalTaskTransitionException(String message) {
        super(message);
    }
}
