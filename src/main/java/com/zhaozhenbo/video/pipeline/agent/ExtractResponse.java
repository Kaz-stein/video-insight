package com.zhaozhenbo.video.pipeline.agent;

import java.util.List;

/** Extractor 的 JSON 契约。 */
public record ExtractResponse(List<ExtractedPoint> points) {
    public ExtractResponse {
        points = points == null ? List.of() : List.copyOf(points);
    }
}
