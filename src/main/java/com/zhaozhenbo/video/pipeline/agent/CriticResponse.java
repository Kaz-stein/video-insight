package com.zhaozhenbo.video.pipeline.agent;

import java.util.List;

/** Critic 的 JSON 契约：通过审查的要点下标。 */
public record CriticResponse(List<Integer> consistent) {
    public CriticResponse {
        consistent = consistent == null ? List.of() : List.copyOf(consistent);
    }
}
