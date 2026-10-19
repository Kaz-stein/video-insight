package com.zhaozhenbo.video.pipeline.agent;

import java.util.List;

public record Plan(List<PlanSection> sections) {

    public Plan {
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }
}
