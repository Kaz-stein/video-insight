package com.zhaozhenbo.video.pipeline.agent;

import com.zhaozhenbo.video.domain.Segment;

import java.util.List;

public record PlannerInput(List<Segment> segments) {
    public PlannerInput {
        segments = List.copyOf(segments);
    }
}
