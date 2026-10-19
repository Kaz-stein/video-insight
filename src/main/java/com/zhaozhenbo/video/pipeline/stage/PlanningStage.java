package com.zhaozhenbo.video.pipeline.stage;

import com.zhaozhenbo.video.application.TaskContext;
import com.zhaozhenbo.video.domain.ArtifactKind;
import com.zhaozhenbo.video.domain.Stage;
import com.zhaozhenbo.video.pipeline.SegmentArtifact;
import com.zhaozhenbo.video.pipeline.agent.Plan;
import com.zhaozhenbo.video.pipeline.agent.Planner;
import com.zhaozhenbo.video.port.ArtifactStorePort;
import com.zhaozhenbo.video.port.StageHandler;

/** PLANNING：章节规划。Planner 内部已处理失败降级，这里只负责存取。 */
public class PlanningStage implements StageHandler {

    private final Planner planner;
    private final ArtifactStorePort artifacts;

    public PlanningStage(Planner planner, ArtifactStorePort artifacts) {
        this.planner = planner;
        this.artifacts = artifacts;
    }

    @Override
    public Stage stage() {
        return Stage.PLANNING;
    }

    @Override
    public void execute(TaskContext context) {
        if (artifacts.load(context.fingerprint(), ArtifactKind.PLAN, Plan.class).isPresent()) {
            return;
        }
        SegmentArtifact segments = artifacts.load(context.fingerprint(), ArtifactKind.SEGMENT, SegmentArtifact.class)
                .orElseThrow(() -> new IllegalStateException("SEGMENT artifact 不存在"));
        Plan plan = planner.plan(segments.segments());
        if (plan.isEmpty()) {
            throw new IllegalStateException("章节规划为空");
        }
        artifacts.save(context.fingerprint(), ArtifactKind.PLAN, plan);
    }
}
