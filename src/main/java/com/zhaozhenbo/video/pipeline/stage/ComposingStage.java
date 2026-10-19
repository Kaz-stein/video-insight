package com.zhaozhenbo.video.pipeline.stage;

import com.zhaozhenbo.video.application.TaskContext;
import com.zhaozhenbo.video.domain.ArtifactKind;
import com.zhaozhenbo.video.domain.Segment;
import com.zhaozhenbo.video.domain.Stage;
import com.zhaozhenbo.video.pipeline.SegmentArtifact;
import com.zhaozhenbo.video.pipeline.VerificationArtifact;
import com.zhaozhenbo.video.pipeline.agent.Composer;
import com.zhaozhenbo.video.pipeline.agent.ComposerInput;
import com.zhaozhenbo.video.pipeline.agent.NoteDraft;
import com.zhaozhenbo.video.pipeline.agent.Plan;
import com.zhaozhenbo.video.pipeline.agent.PlanSection;
import com.zhaozhenbo.video.pipeline.agent.QualityGateException;
import com.zhaozhenbo.video.port.ArtifactStorePort;
import com.zhaozhenbo.video.port.NoteStorePort;
import com.zhaozhenbo.video.port.StageHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** COMPOSING：成稿 + 质量门槛。不达标直接失败，不落“看起来像笔记”的垃圾。 */
public class ComposingStage implements StageHandler {

    private final Composer composer;
    private final ArtifactStorePort artifacts;
    private final NoteStorePort notes;
    private final String model;

    public ComposingStage(Composer composer,
                          ArtifactStorePort artifacts,
                          NoteStorePort notes,
                          String model) {
        this.composer = composer;
        this.artifacts = artifacts;
        this.notes = notes;
        this.model = model;
    }

    @Override
    public Stage stage() {
        return Stage.COMPOSING;
    }

    @Override
    public void execute(TaskContext context) {
        // 以数据库里的 note 为准：artifact 写了但 note 没落时会重算，避免“假完成”
        if (notes.findByTask(context.taskId(), context.userId(), context.fingerprint()).isPresent()) {
            return;
        }
        Plan plan = artifacts.load(context.fingerprint(), ArtifactKind.PLAN, Plan.class)
                .orElseThrow(() -> new IllegalStateException("PLAN artifact 不存在"));
        VerificationArtifact verification = artifacts.load(
                        context.fingerprint(), ArtifactKind.VERIFY, VerificationArtifact.class)
                .orElseThrow(() -> new IllegalStateException("VERIFY artifact 不存在"));
        SegmentArtifact segments = artifacts.load(
                        context.fingerprint(), ArtifactKind.SEGMENT, SegmentArtifact.class)
                .orElseThrow(() -> new IllegalStateException("SEGMENT artifact 不存在"));

        String title = context.title() == null || context.title().isBlank()
                ? "视频笔记"
                : context.title();
        NoteDraft draft = composer.compose(
                title, plan, sectionTexts(plan, segments.segments()), verification.points(), model);
        if (!draft.passesQualityGate()) {
            throw new QualityGateException(
                    "笔记可回溯率仅 " + Math.round(draft.groundedRatio() * 100)
                            + "%，低于 40% 门槛");
        }
        notes.save(context.userId(), context.fingerprint(), context.taskId(), draft);
        artifacts.save(context.fingerprint(), ArtifactKind.NOTE, draft);
    }

    /** 把每个章节覆盖的语义段拼成原文，成稿阶段才能写出解释而不只是复述要点。 */
    private static List<ComposerInput.SectionText> sectionTexts(Plan plan, List<Segment> segments) {
        Map<Integer, Segment> byIndex = new HashMap<>();
        for (Segment segment : segments) {
            byIndex.put(segment.index(), segment);
        }
        List<ComposerInput.SectionText> sections = new ArrayList<>();
        for (PlanSection section : plan.sections()) {
            String text = section.segmentIndexes().stream()
                    .map(byIndex::get)
                    .filter(Objects::nonNull)
                    // 带上段落起点时间：否则模型只能把整章的时间戳复制给每一条，
                    // 表现为一个章节里所有要点都是同一个 [mm:ss]。
                    .map(segment -> "[" + mmss(segment.startMs()) + "] " + segment.text())
                    .collect(Collectors.joining("\n"));
            if (!text.isBlank()) {
                sections.add(new ComposerInput.SectionText(section.title(), text));
            }
        }
        return sections;
    }

    private static String mmss(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
