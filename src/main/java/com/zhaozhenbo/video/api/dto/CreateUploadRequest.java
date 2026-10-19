package com.zhaozhenbo.video.api.dto;

public record CreateUploadRequest(
        String fileName,
        long totalSize,
        int totalChunks,
        long chunkSize
) {
}
