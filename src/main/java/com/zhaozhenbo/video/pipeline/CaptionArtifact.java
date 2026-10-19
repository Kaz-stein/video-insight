package com.zhaozhenbo.video.pipeline;

import com.zhaozhenbo.video.domain.CaptionCue;

import java.util.List;

public record CaptionArtifact(List<CaptionCue> cues) {
    public CaptionArtifact {
        cues = cues == null ? List.of() : List.copyOf(cues);
    }
}
