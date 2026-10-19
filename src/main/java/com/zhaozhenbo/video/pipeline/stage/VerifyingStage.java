package com.zhaozhenbo.video.pipeline.stage;

import com.zhaozhenbo.video.application.TaskContext;
import com.zhaozhenbo.video.domain.ArtifactKind;
import com.zhaozhenbo.video.domain.Stage;
import com.zhaozhenbo.video.pipeline.EvidenceVerifier;
import com.zhaozhenbo.video.pipeline.SegmentArtifact;
import com.zhaozhenbo.video.pipeline.VerificationArtifact;
import com.zhaozhenbo.video.pipeline.agent.Critic;
import com.zhaozhenbo.video.pipeline.agent.CriticResult;
import com.zhaozhenbo.video.pipeline.agent.ExtractionResult;
import com.zhaozhenbo.video.pipeline.agent.VerifiedPoint;
import com.zhaozhenbo.video.port.ArtifactStorePort;
import com.zhaozhenbo.video.port.StageHandler;

import java.util.List;

/** VERIFYING：3-gram 区间校验 + Critic 语义校验。 */
public class VerifyingStage implements StageHandler {

    private final Critic critic;
    private final EvidenceVerifier verifier;
    private final ArtifactStorePort artifacts;

    public VerifyingStage(Critic critic, EvidenceVerifier verifier, ArtifactStorePort artifacts) {
        this.critic = critic;
        this.verifier = verifier;
        this.artifacts = artifacts;
    }

    @Override
    public Stage stage() {
        return Stage.VERIFYING;
    }

    @Override
    public void execute(TaskContext context) {
        if (artifacts.load(context.fingerprint(), ArtifactKind.VERIFY, VerificationArtifact.class).isPresent()) {
            return;
        }
        SegmentArtifact segments = artifacts.load(context.fingerprint(), ArtifactKind.SEGMENT, SegmentArtifact.class)
                .orElseThrow(() -> new IllegalStateException("SEGMENT artifact 不存在"));
        ExtractionResult extraction = artifacts.load(context.fingerprint(), ArtifactKind.EXTRACT, ExtractionResult.class)
                .orElseThrow(() -> new IllegalStateException("EXTRACT artifact 不存在"));

        CriticResult criticResult = critic.review(extraction.points());
        List<VerifiedPoint> verified = verifier.verifyAll(
                extraction.points(), segments.segments(), criticResult);
        artifacts.save(context.fingerprint(), ArtifactKind.VERIFY,
                new VerificationArtifact(verified, criticResult.available()));
    }
}
