package com.zhaozhenbo.video.pipeline.agent;

import java.util.List;

public record OutlineSection(String title, List<Integer> pointSeqs) {

    public OutlineSection {
        title = title == null ? "" : title.strip();
        pointSeqs = pointSeqs == null ? List.of() : List.copyOf(pointSeqs);
    }
}
