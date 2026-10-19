package com.zhaozhenbo.video.api.dto;

import com.zhaozhenbo.video.adapter.persistence.VideoTaskEntity;

import java.time.LocalDateTime;

/**
 * 任务列表里的一行。
 *
 * 只带列表需要的字段，不把整段 error 堆栈、sourceRef 之类塞进去，
 * 免得历史面板一次拉几十条把响应撑大。
 */
public record TaskSummaryResponse(
        long id,
        String title,
        String status,
        String stage,
        String sourceType,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static TaskSummaryResponse from(VideoTaskEntity task) {
        return new TaskSummaryResponse(
                task.getId(),
                task.getTitle(),
                task.getStatus() == null ? null : task.getStatus().name(),
                task.getStage() == null ? null : task.getStage().name(),
                task.getSourceType(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }
}
