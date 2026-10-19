package com.zhaozhenbo.video.pipeline.stage;

import com.zhaozhenbo.video.application.TaskContext;
import com.zhaozhenbo.video.domain.ArtifactKind;
import com.zhaozhenbo.video.domain.Segment;
import com.zhaozhenbo.video.domain.Stage;
import com.zhaozhenbo.video.pipeline.CaptionArtifact;
import com.zhaozhenbo.video.pipeline.SegmentArtifact;
import com.zhaozhenbo.video.pipeline.Segmenter;
import com.zhaozhenbo.video.port.ArtifactStorePort;
import com.zhaozhenbo.video.port.StageHandler;

import java.util.List;

/** SEGMENTING：字幕 -> 带时间轴的语义段。 */
public class SegmentingStage implements StageHandler {

    private final Segmenter segmenter;
    private final ArtifactStorePort artifacts;

    public SegmentingStage(Segmenter segmenter, ArtifactStorePort artifacts) {
        this.segmenter = segmenter;
        this.artifacts = artifacts;
    }

    @Override
    public Stage stage() {
        return Stage.SEGMENTING;
    }

    @Override
    public void execute(TaskContext context) {
        if (artifacts.load(context.fingerprint(), ArtifactKind.SEGMENT, SegmentArtifact.class).isPresent()) {
            return;
        }
        CaptionArtifact captions = artifacts.load(context.fingerprint(), ArtifactKind.CAPTION, CaptionArtifact.class)
                .orElseThrow(() -> new IllegalStateException("CAPTION artifact 不存在"));
        List<Segment> segments = segmenter.segment(captions.cues());
        if (segments.isEmpty()) {
            throw new IllegalStateException("切段结果为空");
        }
        artifacts.save(context.fingerprint(), ArtifactKind.SEGMENT, new SegmentArtifact(segments));
    }
}
