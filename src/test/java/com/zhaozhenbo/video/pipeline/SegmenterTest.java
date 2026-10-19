package com.zhaozhenbo.video.pipeline;

import com.zhaozhenbo.video.domain.CaptionCue;
import com.zhaozhenbo.video.domain.Segment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SegmenterTest {

    private final Segmenter segmenter = new Segmenter();

    @Test
    @DisplayName("超过软上限且句末结束 → 在语义边界切开")
    void cutsAtSentenceBoundary() {
        List<CaptionCue> cues = cues(20, 5_000, "这是一句完整的讲解内容。");

        List<Segment> segments = segmenter.segment(cues);

        assertThat(segments).hasSizeGreaterThanOrEqualTo(2);
        assertThat(segments).allSatisfy(segment ->
                assertThat(segment.durationMs()).isLessThanOrEqualTo(120_000));
        assertThat(segments.get(0).durationMs()).isBetween(20_000L, 120_000L);
    }

    @Test
    @DisplayName("明显停顿（> 5s）必须断段，哪怕当前段很短")
    void cutsAtLongPause() {
        List<CaptionCue> cues = List.of(
                new CaptionCue(0, 0, 2_000, "第一段内容。"),
                new CaptionCue(1, 8_000, 10_000, "第二段内容。")
        );

        List<Segment> segments = segmenter.segment(cues);

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).endMs()).isEqualTo(2_000);
        assertThat(segments.get(1).startMs()).isEqualTo(8_000);
    }

    @Test
    @DisplayName("单条字幕 200s：按比例拆分，所有段不超硬上限")
    void splitsOverlongSingleCue() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            text.append("这是第").append(i).append("句用来测试超长字幕切分的句子。");
        }
        List<CaptionCue> cues = List.of(new CaptionCue(0, 0, 200_000, text.toString()));

        List<Segment> segments = segmenter.segment(cues);

        assertThat(segments).hasSizeGreaterThanOrEqualTo(2);
        assertThat(segments).allSatisfy(segment ->
                assertThat(segment.durationMs()).isLessThanOrEqualTo(120_000));
        assertThat(segments.getLast().endMs()).isEqualTo(200_000);
    }

    @Test
    @DisplayName("碎段合并：连续 1s 字幕应并成一段，而不是生成一堆碎片")
    void mergesTinySegments() {
        List<CaptionCue> cues = List.of(
                new CaptionCue(0, 0, 1_000, "很短的一句。"),
                new CaptionCue(1, 1_000, 2_000, "还是很短。"),
                new CaptionCue(2, 2_000, 3_000, "继续很短。")
        );

        List<Segment> segments = segmenter.segment(cues);

        assertThat(segments).hasSize(1);
        assertThat(segments.getFirst().startMs()).isZero();
        assertThat(segments.getFirst().endMs()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("总时长误差 < 1 秒：切段不能丢时间")
    void keepsTotalDuration() {
        List<CaptionCue> cues = cues(40, 3_700, "每一句都带时间轴。");
        long expectedSpan = cues.getLast().endMs() - cues.getFirst().startMs();

        List<Segment> segments = segmenter.segment(cues);
        long actualSpan = segments.getLast().endMs() - segments.getFirst().startMs();

        assertThat(segments).isNotEmpty();
        assertThat(Math.abs(actualSpan - expectedSpan)).isLessThan(1_000);
        assertThat(segments).allSatisfy(segment -> assertThat(segment.text()).isNotBlank());
        // 段与段首尾相接，播放器跳转不会出现“黑洞”
        for (int i = 1; i < segments.size(); i++) {
            assertThat(segments.get(i).startMs())
                    .isEqualTo(segments.get(i - 1).endMs());
        }
    }

    @Test
    @DisplayName("空列表返回空结果，由调用方决定是否算失败")
    void handlesEmptyInput() {
        assertThat(segmenter.segment(List.of())).isEmpty();
        assertThat(segmenter.segment(null)).isEmpty();
    }

    private static List<CaptionCue> cues(int count, long stepMs, String text) {
        List<CaptionCue> cues = new ArrayList<>(count);
        long cursor = 0;
        for (int i = 0; i < count; i++) {
            cues.add(new CaptionCue(i, cursor, cursor + stepMs, text));
            cursor += stepMs;
        }
        return cues;
    }
}
