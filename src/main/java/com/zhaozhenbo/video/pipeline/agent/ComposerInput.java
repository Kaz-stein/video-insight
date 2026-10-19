package com.zhaozhenbo.video.pipeline.agent;

import java.util.List;

public record ComposerInput(String title, Plan plan, List<VerifiedPoint> points) {
    public ComposerInput {
        points = List.copyOf(points);
    }
}
