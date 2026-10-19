package com.zhaozhenbo.video.pipeline;

import com.zhaozhenbo.video.domain.Segment;

import java.util.List;

public record SegmentArtifact(List<Segment> segments) {
    public SegmentArtifact {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }
}
