package com.zhaozhenbo.video.api.dto;

import com.zhaozhenbo.video.adapter.persistence.VideoTaskEntity;

import java.time.LocalDateTime;

public record TaskResponse(
        long id,
        String sourceType,
        String status,
        String stage,
        String title,
        String sourceRef,
        String error,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static TaskResponse from(VideoTaskEntity task) {
        return new TaskResponse(
                task.getId(),
                task.getSourceType(),
                task.getStatus() == null ? null : task.getStatus().name(),
                task.getStage() == null ? null : task.getStage().name(),
                task.getTitle(),
                // 只有链接类任务才回传原始地址，文件任务是本地路径，不该出现在响应里
                "BILIBILI".equals(task.getSourceType()) ? task.getSourceRef() : null,
                task.getError(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }
}
