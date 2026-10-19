package com.zhaozhenbo.video.port;

public record StoredPoint(
        int seq,
        String text,
        String quote,
        String section,
        long startMs,
        long endMs,
        double overlap,
        String status
) {
}
