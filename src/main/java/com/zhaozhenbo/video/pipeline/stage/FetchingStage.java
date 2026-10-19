package com.zhaozhenbo.video.pipeline.stage;

import com.zhaozhenbo.video.application.TaskContext;
import com.zhaozhenbo.video.domain.ArtifactKind;
import com.zhaozhenbo.video.domain.CaptionCue;
import com.zhaozhenbo.video.domain.Stage;
import com.zhaozhenbo.video.pipeline.CaptionArtifact;
import com.zhaozhenbo.video.port.ArtifactStorePort;
import com.zhaozhenbo.video.port.CaptionSourcePort;
import com.zhaozhenbo.video.port.StageHandler;

import java.util.List;

/** FETCHING：读取字幕并落 artifact。 */
public class FetchingStage implements StageHandler {

    private final CaptionSourcePort captionSource;
    private final ArtifactStorePort artifacts;

    public FetchingStage(CaptionSourcePort captionSource, ArtifactStorePort artifacts) {
        this.captionSource = captionSource;
        this.artifacts = artifacts;
    }

    @Override
    public Stage stage() {
        return Stage.FETCHING;
    }

    @Override
    public void execute(TaskContext context) {
        // 同一指纹的产物命中即跳过，跨任务复用；阶段版本已经带在 stageKey 里
        if (artifacts.load(context.fingerprint(), ArtifactKind.CAPTION, CaptionArtifact.class).isPresent()) {
            return;
        }
        List<CaptionCue> cues = captionSource.load(context.sourceRef());
        artifacts.save(context.fingerprint(), ArtifactKind.CAPTION, new CaptionArtifact(cues));
    }
}
