package com.zhaozhenbo.video.pipeline;

import com.zhaozhenbo.video.domain.Segment;
import com.zhaozhenbo.video.pipeline.agent.CriticResult;
import com.zhaozhenbo.video.pipeline.agent.ExtractedPoint;
import com.zhaozhenbo.video.pipeline.agent.PointStatus;
import com.zhaozhenbo.video.pipeline.agent.VerifiedPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceVerifierTest {

    private final EvidenceVerifier verifier = new EvidenceVerifier();

    private static final List<Segment> SEGMENTS = List.of(
            new Segment(0, 0, 10_000,
                    "Java 线程池的核心参数包括核心线程数、最大线程数和队列长度。", 0, 0),
            new Segment(1, 10_000, 20_000,
                    "当任务数量超过核心线程数时，任务会先进入阻塞队列等待。", 1, 1),
            new Segment(2, 20_000, 30_000,
                    "只有队列满了，线程池才会创建非核心线程。", 2, 2)
    );

    private static final CriticResult ALL_OK = new CriticResult(true, Set.of(0, 1, 2, 3, 4, 5));

    @Test
    @DisplayName("原文完全一致 -> GROUNDED，overlap = 1")
    void exactQuoteIsGrounded() {
        ExtractedPoint point = new ExtractedPoint(
                "线程池的核心参数有核心线程数、最大线程数和队列长度",
                "Java 线程池的核心参数包括核心线程数、最大线程数和队列长度。",
                0, 10_000, "线程池基础");

        VerifiedPoint verified = verifier.verify(0, point, SEGMENTS, ALL_OK);

        assertThat(verified.status()).isEqualTo(PointStatus.GROUNDED);
        assertThat(verified.overlap()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("改写/部分重合 -> INFERRED，不能当事实")
    void partialQuoteIsInferred() {
        ExtractedPoint point = new ExtractedPoint(
                "线程池的参数主要是核心线程数和最大线程数",
                "线程池的核心参数是核心线程数和最大线程数，队列长度并不重要",
                0, 10_000, "线程池基础");

        VerifiedPoint verified = verifier.verify(0, point, SEGMENTS, ALL_OK);

        assertThat(verified.status()).isEqualTo(PointStatus.INFERRED);
        assertThat(verified.overlap()).isBetween(0.3, 0.6);
    }

    @Test
    @DisplayName("原文里没有 -> REJECTED")
    void unrelatedQuoteIsRejected() {
        ExtractedPoint point = new ExtractedPoint(
                "线程池越大越好",
                "今天天气不错，我们去公园散步",
                0, 10_000, "线程池基础");

        VerifiedPoint verified = verifier.verify(0, point, SEGMENTS, ALL_OK);

        assertThat(verified.status()).isEqualTo(PointStatus.REJECTED);
        assertThat(verified.overlap()).isLessThan(0.3);
    }

    @Test
    @DisplayName("时间区间不在任何段落 -> REJECTED（防止跨段拼凑）")
    void outOfRangeQuoteIsRejected() {
        ExtractedPoint point = new ExtractedPoint(
                "线程池的核心参数",
                "Java 线程池的核心参数包括核心线程数、最大线程数和队列长度。",
                30_000, 40_000, "线程池基础");

        VerifiedPoint verified = verifier.verify(0, point, SEGMENTS, ALL_OK);

        assertThat(verified.status()).isEqualTo(PointStatus.REJECTED);
        assertThat(verified.reason()).contains("时间区间");
    }

    @Test
    @DisplayName("Critic 不可用 -> 有原文依据的要点降为 INFERRED")
    void criticUnavailableDowngrades() {
        ExtractedPoint point = new ExtractedPoint(
                "线程池的核心参数有核心线程数、最大线程数和队列长度",
                "Java 线程池的核心参数包括核心线程数、最大线程数和队列长度。",
                0, 10_000, "线程池基础");

        VerifiedPoint verified = verifier.verify(0, point, SEGMENTS, CriticResult.unavailable());

        assertThat(verified.status()).isEqualTo(PointStatus.INFERRED);
        assertThat(verified.reason()).contains("Critic");
    }

    @Test
    @DisplayName("Critic 判定不支持 -> INFERRED")
    void criticRejectsDowngrades() {
        ExtractedPoint point = new ExtractedPoint(
                "线程池的核心参数有核心线程数、最大线程数和队列长度",
                "Java 线程池的核心参数包括核心线程数、最大线程数和队列长度。",
                0, 10_000, "线程池基础");

        VerifiedPoint verified = verifier.verify(0, point, SEGMENTS,
                new CriticResult(true, Set.of()));

        assertThat(verified.status()).isEqualTo(PointStatus.INFERRED);
        assertThat(verified.reason()).contains("Critic");
    }
}
