package com.zhaozhenbo.video.pipeline.stage;

import com.zhaozhenbo.video.application.TaskContext;
import com.zhaozhenbo.video.domain.ArtifactKind;
import com.zhaozhenbo.video.domain.Stage;
import com.zhaozhenbo.video.pipeline.VerificationArtifact;
import com.zhaozhenbo.video.pipeline.agent.Composer;
import com.zhaozhenbo.video.pipeline.agent.NoteDraft;
import com.zhaozhenbo.video.pipeline.agent.Plan;
import com.zhaozhenbo.video.pipeline.agent.QualityGateException;
import com.zhaozhenbo.video.port.ArtifactStorePort;
import com.zhaozhenbo.video.port.StageHandler;

/** COMPOSING：成稿 + 质量门槛。不达标直接失败，不落“看起来像笔记”的垃圾。 */
public class ComposingStage implements StageHandler {

    private final Composer composer;
    private final ArtifactStorePort artifacts;
    private final String model;

    public ComposingStage(Composer composer, ArtifactStorePort artifacts, String model) {
        this.composer = composer;
        this.artifacts = artifacts;
        this.model = model;
    }

    @Override
    public Stage stage() {
        return Stage.COMPOSING;
    }

    @Override
    public void execute(TaskContext context) {
        if (artifacts.load(context.fingerprint(), ArtifactKind.NOTE, NoteDraft.class).isPresent()) {
            return;
        }
        Plan plan = artifacts.load(context.fingerprint(), ArtifactKind.PLAN, Plan.class)
                .orElseThrow(() -> new IllegalStateException("PLAN artifact 不存在"));
        VerificationArtifact verification = artifacts.load(
                        context.fingerprint(), ArtifactKind.VERIFY, VerificationArtifact.class)
                .orElseThrow(() -> new IllegalStateException("VERIFY artifact 不存在"));

        String title = context.title() == null || context.title().isBlank()
                ? "视频笔记"
                : context.title();
        NoteDraft draft = composer.compose(title, plan, verification.points(), model);
        if (!draft.passesQualityGate()) {
            throw new QualityGateException(
                    "笔记可回溯率仅 " + Math.round(draft.groundedRatio() * 100)
                            + "%，低于 40% 门槛");
        }
        artifacts.save(context.fingerprint(), ArtifactKind.NOTE, draft);
    }
}
