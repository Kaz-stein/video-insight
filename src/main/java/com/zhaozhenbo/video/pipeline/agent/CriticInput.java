package com.zhaozhenbo.video.pipeline.agent;

import java.util.List;

public record CriticInput(List<ExtractedPoint> points) {
    public CriticInput {
        points = List.copyOf(points);
    }
}
