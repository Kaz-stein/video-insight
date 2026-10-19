package com.zhaozhenbo.video.pipeline;

import com.zhaozhenbo.video.pipeline.agent.VerifiedPoint;

import java.util.List;

public record VerificationArtifact(List<VerifiedPoint> points, boolean criticAvailable) {
    public VerificationArtifact {
        points = points == null ? List.of() : List.copyOf(points);
    }
}
