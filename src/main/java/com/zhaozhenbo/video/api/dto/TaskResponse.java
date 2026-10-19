package com.zhaozhenbo.video.api.dto;

import com.zhaozhenbo.video.adapter.persistence.VideoTaskEntity;

import java.time.LocalDateTime;

public record TaskResponse(
        long id,
        String status,
        String stage,
        String title,
        String error,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static TaskResponse from(VideoTaskEntity task) {
        return new TaskResponse(
                task.getId(),
                task.getStatus() == null ? null : task.getStatus().name(),
                task.getStage() == null ? null : task.getStage().name(),
                task.getTitle(),
                task.getError(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }
}
