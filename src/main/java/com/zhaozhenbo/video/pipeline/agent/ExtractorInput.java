package com.zhaozhenbo.video.pipeline.agent;

import com.zhaozhenbo.video.domain.Segment;

import java.util.List;

public record ExtractorInput(String sectionTitle, List<Segment> segments) {
    public ExtractorInput {
        segments = List.copyOf(segments);
    }
}
