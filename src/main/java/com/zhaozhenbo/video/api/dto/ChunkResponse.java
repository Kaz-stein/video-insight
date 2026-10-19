package com.zhaozhenbo.video.api.dto;

public record ChunkResponse(int chunkIndex, int uploadedChunks, int totalChunks) {
}
