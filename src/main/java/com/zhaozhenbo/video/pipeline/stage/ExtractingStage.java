package com.zhaozhenbo.video.pipeline.stage;

import com.zhaozhenbo.video.application.TaskContext;
import com.zhaozhenbo.video.domain.ArtifactKind;
import com.zhaozhenbo.video.domain.Stage;
import com.zhaozhenbo.video.pipeline.SegmentArtifact;
import com.zhaozhenbo.video.pipeline.agent.ExtractionResult;
import com.zhaozhenbo.video.pipeline.agent.Extractor;
import com.zhaozhenbo.video.pipeline.agent.Plan;
import com.zhaozhenbo.video.port.ArtifactStorePort;
import com.zhaozhenbo.video.port.StageHandler;

/** EXTRACTING：按章节抽要点，允许部分章节失败。 */
public class ExtractingStage implements StageHandler {

    private final Extractor extractor;
    private final ArtifactStorePort artifacts;

    public ExtractingStage(Extractor extractor, ArtifactStorePort artifacts) {
        this.extractor = extractor;
        this.artifacts = artifacts;
    }

    @Override
    public Stage stage() {
        return Stage.EXTRACTING;
    }

    @Override
    public void execute(TaskContext context) {
        if (artifacts.load(context.fingerprint(), ArtifactKind.EXTRACT, ExtractionResult.class).isPresent()) {
            return;
        }
        SegmentArtifact segments = artifacts.load(context.fingerprint(), ArtifactKind.SEGMENT, SegmentArtifact.class)
                .orElseThrow(() -> new IllegalStateException("SEGMENT artifact 不存在"));
        Plan plan = artifacts.load(context.fingerprint(), ArtifactKind.PLAN, Plan.class)
                .orElseThrow(() -> new IllegalStateException("PLAN artifact 不存在"));
        ExtractionResult result = extractor.extract(plan, segments.segments());
        artifacts.save(context.fingerprint(), ArtifactKind.EXTRACT, result);
    }
}
