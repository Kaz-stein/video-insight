package com.zhaozhenbo.video.pipeline.agent;

import com.zhaozhenbo.video.adapter.llm.FakeLlmAdapter;
import com.zhaozhenbo.video.domain.LlmStep;
import com.zhaozhenbo.video.domain.Segment;
import com.zhaozhenbo.video.pipeline.EvidenceVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPipelineTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("Planner 失败 -> 按每 5 段降级，仍覆盖全部段落")
    void plannerFallsBack() {
        FakeLlmAdapter llm = new FakeLlmAdapter(mapper);
        llm.failAlways(LlmStep.PLANNER);
        Planner planner = new Planner(llm, mapper);

        Plan plan = planner.plan(segments(12));

        assertThat(plan.sections()).hasSize(3);
        assertThat(plan.sections().stream()
                .flatMap(section -> section.segmentIndexes().stream())
                .distinct()
                .count()).isEqualTo(12);
    }

    @Test
    @DisplayName("Extractor 部分章节失败 -> 保留成功章节，并记录失败章节")
    void extractorKeepsPartialResult() {
        FakeLlmAdapter llm = new FakeLlmAdapter(mapper);
        Planner planner = new Planner(llm, mapper);
        Plan plan = planner.plan(segments(6));
        llm.failNext(LlmStep.EXTRACTOR);
        Extractor extractor = new Extractor(llm, mapper);

        ExtractionResult result = extractor.extract(plan, segments(6));

        assertThat(result.failedSections()).hasSize(1);
        assertThat(result.points()).isNotEmpty();
        assertThat(result.points().size()).isLessThan(6);
    }

    @Test
    @DisplayName("Critic 失败 -> 全部非 REJECTED 要点标 INFERRED")
    void criticFailureMarksAllInferred() {
        FakeLlmAdapter llm = new FakeLlmAdapter(mapper);
        Planner planner = new Planner(llm, mapper);
        Plan plan = planner.plan(segments(3));
        Extractor extractor = new Extractor(llm, mapper);
        ExtractionResult extraction = extractor.extract(plan, segments(3));

        llm.failAlways(LlmStep.CRITIC);
        CriticResult critic = new Critic(llm, mapper).review(extraction.points());
        List<VerifiedPoint> verified = new EvidenceVerifier()
                .verifyAll(extraction.points(), segments(3), critic);

        assertThat(critic.available()).isFalse();
        assertThat(verified)
                .filteredOn(point -> point.status() != PointStatus.REJECTED)
                .allSatisfy(point -> assertThat(point.status()).isEqualTo(PointStatus.INFERRED));
    }

    @Test
    @DisplayName("Composer 失败 -> 机械拼接仍有标题、要点和时间轴")
    void composerFallbackStillUsable() {
        FakeLlmAdapter llm = new FakeLlmAdapter(mapper);
        Planner planner = new Planner(llm, mapper);
        Plan plan = planner.plan(segments(3));
        ExtractionResult extraction = new Extractor(llm, mapper).extract(plan, segments(3));
        CriticResult critic = new Critic(llm, mapper).review(extraction.points());
        List<VerifiedPoint> verified = new EvidenceVerifier()
                .verifyAll(extraction.points(), segments(3), critic);

        llm.failAlways(LlmStep.COMPOSER);
        NoteDraft draft = new Composer(llm, mapper)
                .compose("线程池讲解", plan, verified, "fake-model");

        assertThat(draft.markdown()).contains("# 线程池讲解");
        assertThat(draft.markdown()).contains("## 内容总结");
        assertThat(draft.markdown()).contains("## 时间轴");
        assertThat(draft.markdown()).contains("00:00");
        assertThat(draft.points()).isNotEmpty();
    }

    @Test
    @DisplayName("正常路径：四步走完，全部要点 GROUNDED，质量门槛通过")
    void happyPath() {
        FakeLlmAdapter llm = new FakeLlmAdapter(mapper);
        List<Segment> segments = segments(6);
        Plan plan = new Planner(llm, mapper).plan(segments);
        ExtractionResult extraction = new Extractor(llm, mapper).extract(plan, segments);
        CriticResult critic = new Critic(llm, mapper).review(extraction.points());
        List<VerifiedPoint> verified = new EvidenceVerifier()
                .verifyAll(extraction.points(), segments, critic);
        NoteDraft draft = new Composer(llm, mapper)
                .compose("线程池讲解", plan, verified, "fake-model");

        assertThat(extraction.failedSections()).isEmpty();
        assertThat(verified).allSatisfy(point ->
                assertThat(point.status()).isEqualTo(PointStatus.GROUNDED));
        assertThat(draft.groundedRatio()).isEqualTo(1.0);
        assertThat(draft.passesQualityGate()).isTrue();
        assertThat(draft.outline()).isNotEmpty();
        // 走的是模型稿而不是机械模板：模板正文带这句固定尾巴，模型稿没有
        assertThat(draft.markdown()).startsWith("# 线程池讲解");
        assertThat(draft.markdown()).doesNotContain("证据经后端校验");
        assertThat(draft.summary()).contains("核心结论");
    }

    @Test
    @DisplayName("质量门槛：可回溯率低于 40% 不通过")
    void qualityGateRejectsLowGroundedRatio() {
        VerifiedPoint grounded = point(PointStatus.GROUNDED);
        VerifiedPoint rejected = point(PointStatus.REJECTED);
        NoteDraft draft = new NoteDraft("标题", "摘要", "# 标题", List.of(),
                List.of(grounded, rejected, rejected, rejected), "m", "v");

        assertThat(draft.groundedRatio()).isEqualTo(0.25);
        assertThat(draft.passesQualityGate()).isFalse();
    }

    private static VerifiedPoint point(PointStatus status) {
        return new VerifiedPoint("要点", "原文", "章节", 0, 1000,
                status == PointStatus.GROUNDED ? 1.0 : 0.1, status, "");
    }

    private static List<Segment> segments(int count) {
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long start = i * 10_000L;
            segments.add(new Segment(i, start, start + 10_000,
                    "第" + (i + 1) + "段讲解线程池的核心参数和队列之间的关系。", i, i));
        }
        return segments;
    }
}
