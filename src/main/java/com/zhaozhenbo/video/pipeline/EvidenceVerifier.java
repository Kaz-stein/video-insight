package com.zhaozhenbo.video.pipeline;

import com.zhaozhenbo.video.domain.Segment;
import com.zhaozhenbo.video.pipeline.agent.CriticResult;
import com.zhaozhenbo.video.pipeline.agent.ExtractedPoint;
import com.zhaozhenbo.video.pipeline.agent.PointStatus;
import com.zhaozhenbo.video.pipeline.agent.VerifiedPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 证据校验：模型说“这句话在视频里”不算数，必须由后端算出来。
 *
 * 判定：
 *   overlap >= 0.6          -> GROUNDED
 *   0.3 <= overlap < 0.6    -> 同区间滑动窗口再找一次；仍 < 0.6 -> INFERRED
 *   overlap < 0.3 或区间不存在 -> REJECTED
 *
 * 另外，Critic 不可用时所有非 REJECTED 要点降为 INFERRED——宁可标“推断”，
 * 也不能把没审过的内容当事实。
 */
public final class EvidenceVerifier {

    public static final double GROUNDED_THRESHOLD = 0.6;
    public static final double INFERRED_THRESHOLD = 0.3;

    public List<VerifiedPoint> verifyAll(List<ExtractedPoint> points,
                                         List<Segment> segments,
                                         CriticResult critic) {
        List<VerifiedPoint> result = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            result.add(verify(i, points.get(i), segments, critic));
        }
        return result;
    }

    public VerifiedPoint verify(int pointIndex,
                                ExtractedPoint point,
                                List<Segment> segments,
                                CriticResult critic) {
        List<Segment> overlapping = segments.stream()
                .filter(segment -> segment.endMs() > point.startMs()
                        && segment.startMs() < point.endMs())
                .sorted(Comparator.comparingLong(Segment::startMs))
                .toList();

        if (overlapping.isEmpty()) {
            return new VerifiedPoint(point.text(), point.quote(), point.section(),
                    point.startMs(), point.endMs(), 0, PointStatus.REJECTED,
                    "时间区间不在任何字幕段内");
        }

        String rangeText = String.join(" ", overlapping.stream().map(Segment::text).toList());
        String quoteNorm = TextNormalizer.normalizeForCompare(point.quote());
        String rangeNorm = TextNormalizer.normalizeForCompare(rangeText);
        if (quoteNorm.isEmpty()) {
            return new VerifiedPoint(point.text(), point.quote(), point.section(),
                    point.startMs(), point.endMs(), 0, PointStatus.REJECTED,
                    "引用为空");
        }

        double overlap = overlap(quoteNorm, rangeNorm);
        String quote = point.quote();

        if (overlap < INFERRED_THRESHOLD) {
            return new VerifiedPoint(point.text(), quote, point.section(),
                    point.startMs(), point.endMs(), round(overlap), PointStatus.REJECTED,
                    "引用与时间区间内的原文重合度过低");
        }

        if (overlap < GROUNDED_THRESHOLD) {
            WindowMatch match = bestWindow(quoteNorm, rangeText);
            if (match.overlap() >= GROUNDED_THRESHOLD) {
                overlap = match.overlap();
                quote = match.text();
            } else {
                return new VerifiedPoint(point.text(), quote, point.section(),
                        point.startMs(), point.endMs(), round(overlap), PointStatus.INFERRED,
                        "引用是原文的改写或拼接，未达到可回溯阈值");
            }
        }

        if (!critic.available()) {
            return new VerifiedPoint(point.text(), quote, point.section(),
                    point.startMs(), point.endMs(), round(overlap), PointStatus.INFERRED,
                    "Critic 不可用，未完成语义校验");
        }
        if (!critic.isConsistent(pointIndex)) {
            return new VerifiedPoint(point.text(), quote, point.section(),
                    point.startMs(), point.endMs(), round(overlap), PointStatus.INFERRED,
                    "Critic 判定引用不足以支持该观点");
        }

        return new VerifiedPoint(point.text(), quote, point.section(),
                point.startMs(), point.endMs(), round(overlap), PointStatus.GROUNDED, "");
    }

    /**
     * 3-gram 重合度（多重集）。用计数而不是集合，避免重复词把分数刷高。
     */
    static double overlap(String quoteNorm, String rangeNorm) {
        if (quoteNorm.isEmpty()) {
            return 0;
        }
        if (quoteNorm.length() < 3) {
            return rangeNorm.contains(quoteNorm) ? 1.0 : 0.0;
        }
        Map<String, Integer> quoteGrams = grams(quoteNorm);
        int total = quoteGrams.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return 0;
        }
        Map<String, Integer> remaining = new HashMap<>(quoteGrams);
        int matched = 0;
        for (int i = 0; i + 3 <= rangeNorm.length(); i++) {
            String gram = rangeNorm.substring(i, i + 3);
            int count = remaining.getOrDefault(gram, 0);
            if (count > 0) {
                remaining.put(gram, count - 1);
                matched++;
            }
        }
        return (double) matched / total;
    }

    private static Map<String, Integer> grams(String text) {
        Map<String, Integer> grams = new HashMap<>();
        for (int i = 0; i + 3 <= text.length(); i++) {
            grams.merge(text.substring(i, i + 3), 1, Integer::sum);
        }
        return grams;
    }

    /**
     * 滑动窗口：模型常把两句话合并、改几个字，整体 3-gram 会掉到 0.4 左右。
     * 在同一段原文里找一个最接近的窗口，能救回大部分“合理改写”。
     */
    private static WindowMatch bestWindow(String quoteNorm, String rangeText) {
        int approx = quoteNorm.length();
        int window = Math.min(rangeText.length(), Math.max(approx, approx * 2));
        int step = Math.max(1, window / 8);
        double best = 0;
        String bestText = "";

        for (int start = 0; start < rangeText.length(); start += step) {
            int end = Math.min(rangeText.length(), start + window);
            String candidate = rangeText.substring(start, end).strip();
            double score = overlap(quoteNorm, TextNormalizer.normalizeForCompare(candidate));
            if (score > best) {
                best = score;
                bestText = candidate;
            }
            if (end == rangeText.length()) {
                break;
            }
        }
        return new WindowMatch(best, bestText);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record WindowMatch(double overlap, String text) {
    }
}
