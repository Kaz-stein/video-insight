package com.zhaozhenbo.video.api.dto;

import com.zhaozhenbo.video.port.StoredPoint;

public record PointResponse(
        int seq,
        String text,
        String quote,
        String section,
        long startMs,
        long endMs,
        double overlap,
        String status
) {
    public static PointResponse from(StoredPoint point) {
        return new PointResponse(point.seq(), point.text(), point.quote(), point.section(),
                point.startMs(), point.endMs(), point.overlap(), point.status());
    }
}
