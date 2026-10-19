package com.zhaozhenbo.video.pipeline;

import com.zhaozhenbo.video.domain.CaptionCue;
import com.zhaozhenbo.video.domain.Segment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把字幕切成“模型一次能读完、但语义完整”的段。
 *
 * 为什么不能按固定 30 秒切：
 *   一句话被从中间切开，模型看不到后半句，抽出来的观点必然残缺。
 * 所以这里用“时间窗 + 语义边界”双约束：
 *   - 软上限 targetMax（默认 75s）：超过它之后，遇到句末标点或明显停顿才切
 *   - 硬上限 hardMax（默认 120s）：再没有边界也必须切，控制单次 prompt 体积
 *   - 超长单条字幕：按时长比例拆成多块，保证总时间轴不变
 */
public final class Segmenter {

    public record Config(long targetMaxMs, long hardMaxMs, long minMs, long pauseMs) {
        public Config {
            if (targetMaxMs <= 0 || hardMaxMs < targetMaxMs || minMs < 0 || pauseMs < 0) {
                throw new IllegalArgumentException("切段参数非法: " + targetMaxMs + "/" + hardMaxMs
                        + "/" + minMs + "/" + pauseMs);
            }
        }

        public static Config defaults() {
            return new Config(75_000, 120_000, 20_000, 800);
        }
    }

    /** 间隔超过 5 秒，基本可以视为换话题，必须断开 */
    private static final long HARD_GAP_MS = 5_000;

    private final Config config;

    public Segmenter() {
        this(Config.defaults());
    }

    public Segmenter(Config config) {
        this.config = config;
    }

    public List<Segment> segment(List<CaptionCue> cues) {
        if (cues == null || cues.isEmpty()) {
            return List.of();
        }

        List<CaptionCue> ordered = cues.stream()
                .filter(cue -> !cue.text().isBlank())
                .sorted(Comparator.comparingLong(CaptionCue::startMs).thenComparingLong(CaptionCue::endMs))
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }

        List<Piece> pieces = expandLongCues(ordered);
        List<MutableSegment> segments = new ArrayList<>();
        MutableSegment current = null;

        for (Piece piece : pieces) {
            if (current == null) {
                current = new MutableSegment(piece.cueIndex(), piece.startMs(), piece.text());
                current.extend(piece.endMs(), piece.cueIndex(), null);
                continue;
            }

            long prospectiveEnd = Math.max(current.endMs(), piece.endMs());
            long prospectiveDuration = prospectiveEnd - current.startMs();
            long gap = piece.startMs() - current.endMs();

            boolean hardExceeded = prospectiveDuration > config.hardMaxMs();
            boolean hugeGap = gap >= HARD_GAP_MS;
            boolean softExceeded = prospectiveDuration > config.targetMaxMs()
                    && current.durationMs() >= config.minMs();
            boolean naturalBoundary = gap >= config.pauseMs() || endsSentence(current.text());

            if (hardExceeded || hugeGap || (softExceeded && naturalBoundary)) {
                segments.add(current);
                current = new MutableSegment(piece.cueIndex(), piece.startMs(), piece.text());
                current.extend(piece.endMs(), piece.cueIndex(), null);
            } else {
                current.extend(piece.endMs(), piece.cueIndex(), piece.text());
            }
        }
        segments.add(current);

        List<MutableSegment> merged = mergeTooShort(segments);
        List<Segment> result = new ArrayList<>(merged.size());
        for (int i = 0; i < merged.size(); i++) {
            MutableSegment segment = merged.get(i);
            result.add(new Segment(i, segment.startMs(), segment.endMs(), segment.text(),
                    segment.firstCue(), segment.lastCue()));
        }
        return result;
    }

    /**
     * 单条字幕超过硬上限（例如整段旁白只给了一条时间轴）时，按时长比例拆开。
     * 时间轴是线性分配的，拆完所有小段首尾相接，总时长保持不变。
     */
    private List<Piece> expandLongCues(List<CaptionCue> cues) {
        List<Piece> pieces = new ArrayList<>(cues.size());
        for (CaptionCue cue : cues) {
            long duration = cue.durationMs();
            if (duration <= config.hardMaxMs() || cue.text().length() < 2) {
                pieces.add(new Piece(cue.index(), cue.startMs(), cue.endMs(), cue.text()));
                continue;
            }

            int estimated = (int) Math.floor(cue.text().length() * (double) config.hardMaxMs() / duration);
            int maxChars = Math.max(1, Math.min(estimated, Math.max(1, cue.text().length() / 2)));
            List<String> chunks = splitIntoChunks(cue.text(), maxChars);
            long totalChars = chunks.stream().mapToLong(String::length).sum();

            long cursor = cue.startMs();
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                long end = (i == chunks.size() - 1)
                        ? cue.endMs()
                        : Math.min(cue.endMs(), cursor + Math.max(1,
                                Math.round(duration * (double) chunk.length() / totalChars)));
                pieces.add(new Piece(cue.index(), cursor, end, chunk));
                cursor = end;
            }
        }
        return pieces;
    }

    private static List<String> splitIntoChunks(String text, int maxChars) {
        List<String> sentences = new ArrayList<>();
        int from = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isSentenceEnd(text.charAt(i))) {
                sentences.add(text.substring(from, i + 1));
                from = i + 1;
            }
        }
        if (from < text.length()) {
            sentences.add(text.substring(from));
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String rawSentence : sentences) {
            String sentence = rawSentence.strip();
            if (sentence.isEmpty()) {
                continue;
            }
            if (sentence.length() > maxChars) {
                if (buffer.length() > 0) {
                    chunks.add(buffer.toString());
                    buffer.setLength(0);
                }
                for (int i = 0; i < sentence.length(); i += maxChars) {
                    chunks.add(sentence.substring(i, Math.min(sentence.length(), i + maxChars)));
                }
                continue;
            }
            if (buffer.length() > 0 && buffer.length() + 1 + sentence.length() > maxChars) {
                chunks.add(buffer.toString());
                buffer.setLength(0);
            }
            if (buffer.length() > 0) {
                buffer.append(' ');
            }
            buffer.append(sentence);
        }
        if (buffer.length() > 0) {
            chunks.add(buffer.toString());
        }
        return chunks.isEmpty() ? List.of(text) : chunks;
    }

    /**
     * 过短的段（< 20s）通常是片尾一句话，单独送给模型没有上下文。
     * 优先并回上一段；若合并后超过硬上限就保持独立。
     */
    private List<MutableSegment> mergeTooShort(List<MutableSegment> segments) {
        List<MutableSegment> merged = new ArrayList<>(segments.size());
        for (MutableSegment segment : segments) {
            if (!merged.isEmpty()) {
                MutableSegment previous = merged.getLast();
                boolean shortPrevious = previous.durationMs() < config.minMs();
                boolean closeEnough = segment.startMs() - previous.endMs() <= HARD_GAP_MS;
                boolean stillWithinHardMax = segment.endMs() - previous.startMs() <= config.hardMaxMs();
                if (shortPrevious && closeEnough && stillWithinHardMax) {
                    previous.absorb(segment);
                    continue;
                }
            }
            merged.add(segment);
        }
        return merged;
    }

    private static boolean endsSentence(String text) {
        String trimmed = text.strip();
        while (!trimmed.isEmpty() && "”\"'）)】」』".indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }
        if (trimmed.isEmpty()) {
            return false;
        }
        char last = trimmed.charAt(trimmed.length() - 1);
        // 英文视频的字幕以 "." 结尾，也要算语义边界
        return "。！？!?；;…".indexOf(last) >= 0 || last == '.';
    }

    private static boolean isSentenceEnd(char c) {
        return "。！？!?；;…".indexOf(c) >= 0 || c == '.';
    }

    private record Piece(int cueIndex, long startMs, long endMs, String text) {
    }

    private static final class MutableSegment {
        private final long startMs;
        private long endMs;
        private int firstCue;
        private int lastCue;
        private final StringBuilder text;

        private MutableSegment(int cueIndex, long startMs, String text) {
            this.startMs = startMs;
            this.endMs = startMs;
            this.firstCue = cueIndex;
            this.lastCue = cueIndex;
            this.text = new StringBuilder(text);
        }

        private void extend(long endMs, int cueIndex, String appended) {
            this.endMs = Math.max(this.endMs, endMs);
            this.lastCue = Math.max(this.lastCue, cueIndex);
            if (appended != null && !appended.isBlank()) {
                String joined = TextNormalizer.join(this.text.toString(), appended);
                this.text.setLength(0);
                this.text.append(joined);
            }
        }

        private void absorb(MutableSegment other) {
            extend(other.endMs, other.lastCue, other.text.toString());
        }

        private long durationMs() {
            return endMs - startMs;
        }

        private long startMs() {
            return startMs;
        }

        private long endMs() {
            return endMs;
        }

        private int firstCue() {
            return firstCue;
        }

        private int lastCue() {
            return lastCue;
        }

        private String text() {
            return text.toString();
        }
    }
}
