package com.zhaozhenbo.video.api.dto;

import java.util.List;

public record UploadStatusResponse(
        String uploadId,
        String status,
        int totalChunks,
        long chunkSize,
        long totalSize,
        List<Integer> uploadedChunks,
        Long taskId
) {
}
