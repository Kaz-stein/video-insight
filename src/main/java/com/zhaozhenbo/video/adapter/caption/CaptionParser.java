package com.zhaozhenbo.video.adapter.caption;

import com.zhaozhenbo.video.domain.CaptionCue;
import com.zhaozhenbo.video.pipeline.TextNormalizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SRT / VTT 解析器。
 *
 * 同时兼容两种格式，是因为各平台导出的字幕并不统一：
 *   - SRT: 00:00:01,000 --> 00:00:04,000（逗号、必须带小时）
 *   - VTT: 00:01.000 --> 00:04.000（点号、小时可省略、时间轴后可跟 cue 设置）
 *
 * 容错点（都是真实文件里踩过的）：
 *   1) BOM 头、\r\n / \r / \n 混用
 *   2) 序号行缺失，或者 cue 之间没有空行
 *   3) 一条字幕占多行，需要合并成一段
 *   4) 文件里 cue 顺序错乱，解析后按开始时间排序并重新编号
 *   5) 字幕里夹 <i>、{\an8} 这类样式标记
 */
public final class CaptionParser {

    private static final Pattern TIMING = Pattern.compile(
            "^\\s*(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{1,2})[.,](\\d{1,3})"
                    + "\\s*-->\\s*"
                    + "(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{1,2})[.,](\\d{1,3}).*$");

    private static final Pattern CUE_INDEX = Pattern.compile("^\\d{1,7}$");

    public List<CaptionCue> parse(String raw) {
        String content = raw == null ? "" : raw.replace("\uFEFF", "");
        if (content.isBlank()) {
            throw new IllegalArgumentException("字幕内容为空");
        }

        String[] lines = content.split("\\r\\n|\\r|\\n", -1);
        List<RawCue> raws = new ArrayList<>();

        Long cueStart = null;
        Long cueEnd = null;
        StringBuilder cueText = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher timing = TIMING.matcher(line.strip());

            if (timing.matches()) {
                if (cueStart != null) {
                    addCue(raws, cueStart, cueEnd, cueText);
                }
                cueStart = toMillis(timing, 1);
                cueEnd = toMillis(timing, 5);
                cueText.setLength(0);
                continue;
            }

            // 还没进入任何 cue：WEBVTT 头、NOTE、STYLE、序号行都直接忽略
            if (cueStart == null) {
                continue;
            }

            if (line.isBlank()) {
                addCue(raws, cueStart, cueEnd, cueText);
                cueStart = null;
                cueEnd = null;
                cueText.setLength(0);
                continue;
            }

            // cue 之间没有空行时，下一段的序号行要当作分隔符
            if (CUE_INDEX.matcher(line.strip()).matches() && nextNonBlankIsTiming(lines, i + 1)) {
                addCue(raws, cueStart, cueEnd, cueText);
                cueStart = null;
                cueEnd = null;
                cueText.setLength(0);
                continue;
            }

            if (cueText.length() > 0) {
                cueText.append(' ');
            }
            cueText.append(line);
        }

        if (cueStart != null) {
            addCue(raws, cueStart, cueEnd, cueText);
        }

        if (raws.isEmpty()) {
            throw new IllegalArgumentException("未解析出任何字幕条目，请确认文件是 SRT 或 VTT 格式");
        }

        // 排序 + 去重：同一文件被重复拼接时不应产生重复证据
        List<RawCue> ordered = raws.stream()
                .sorted(Comparator.comparingLong(RawCue::startMs).thenComparingLong(RawCue::endMs))
                .toList();
        Set<String> seen = new LinkedHashSet<>();
        List<CaptionCue> result = new ArrayList<>(ordered.size());
        for (RawCue rawCue : ordered) {
            String key = rawCue.startMs() + "|" + rawCue.endMs() + "|" + rawCue.text();
            if (!seen.add(key)) {
                continue;
            }
            result.add(new CaptionCue(result.size(), rawCue.startMs(), rawCue.endMs(), rawCue.text()));
        }
        return result;
    }

    private static void addCue(List<RawCue> target, long start, long end, StringBuilder text) {
        String cleaned = TextNormalizer.clean(text.toString());
        if (cleaned.isEmpty()) {
            return; // 只有时间轴没有文字（音乐、音效）不算证据
        }
        target.add(new RawCue(start, Math.max(start, end), cleaned));
    }

    private static boolean nextNonBlankIsTiming(String[] lines, int from) {
        for (int i = from; i < lines.length; i++) {
            String candidate = lines[i].strip();
            if (candidate.isEmpty()) {
                continue;
            }
            return TIMING.matcher(candidate).matches();
        }
        return false;
    }

    private static long toMillis(Matcher matcher, int groupOffset) {
        String hours = matcher.group(groupOffset);
        long h = hours == null ? 0 : Long.parseLong(hours);
        long m = Long.parseLong(matcher.group(groupOffset + 1));
        long s = Long.parseLong(matcher.group(groupOffset + 2));
        String ms = matcher.group(groupOffset + 3);
        long milli = Long.parseLong((ms + "00").substring(0, 3));
        return ((h * 60 + m) * 60 + s) * 1000 + milli;
    }

    private record RawCue(long startMs, long endMs, String text) {
    }
}
