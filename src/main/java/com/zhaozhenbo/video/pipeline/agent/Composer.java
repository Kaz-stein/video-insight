package com.zhaozhenbo.video.pipeline.agent;

import com.zhaozhenbo.video.domain.LlmCall;
import com.zhaozhenbo.video.domain.LlmStep;
import com.zhaozhenbo.video.port.LlmPort;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第四步：成稿。
 *
 * Composer 失败时不能让整篇丢掉：用机械模板把已校验的要点拼起来，
 * 文风差一点，但事实链是完整的。
 */
public class Composer {

    private final LlmPort llm;
    private final ObjectMapper mapper;

    public Composer(LlmPort llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    public NoteDraft compose(String title, Plan plan, List<VerifiedPoint> points, String model) {
        return compose(title, plan, List.of(), points, model);
    }

    public NoteDraft compose(String title,
                             Plan plan,
                             List<ComposerInput.SectionText> sections,
                             List<VerifiedPoint> points,
                             String model) {
        String safeTitle = (title == null || title.isBlank()) ? "视频笔记" : title.strip();
        ComposerOutput output = tryLlm(safeTitle, plan, sections, points);

        String summary = output == null || output.summary() == null || output.summary().isBlank()
                ? mechanicalSummary(points)
                : output.summary().strip();
        String markdown = output == null || output.markdown() == null
                || !isComplete(output.markdown(), safeTitle)
                ? mechanicalMarkdown(safeTitle, plan, points, model, summary)
                : output.markdown();

        return new NoteDraft(
                safeTitle,
                summary,
                markdown,
                buildOutline(plan, points),
                points,
                model,
                Prompts.COMPOSER_VERSION);
    }

    /** 不能让模型少写“时间轴”就交稿：模板缺块直接回退到机械成稿。 */
    private static boolean isComplete(String markdown, String title) {
        return markdown.length() >= 80
                && markdown.contains(title)
                && markdown.contains("## 内容总结")
                && markdown.contains("## 核心观点")
                && markdown.contains("## 时间轴");
    }

    private ComposerOutput tryLlm(String title,
                                  Plan plan,
                                  List<ComposerInput.SectionText> sections,
                                  List<VerifiedPoint> points) {
        try {
            ComposerInput input = new ComposerInput(title, plan, sections, points);
            String inputJson = mapper.writeValueAsString(input);
            String response = llm.complete(new LlmCall(
                    LlmStep.COMPOSER,
                    Prompts.COMPOSER_VERSION,
                    Prompts.composerSystem(),
                    Prompts.composerUser(inputJson),
                    input,
                    0.3,
                    6000));
            return mapper.readValue(response, ComposerOutput.class);
        } catch (Exception e) {
            return null;
        }
    }

    private List<OutlineSection> buildOutline(Plan plan, List<VerifiedPoint> points) {
        Map<String, List<Integer>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < points.size(); i++) {
            VerifiedPoint point = points.get(i);
            if (point.status() == PointStatus.REJECTED) {
                continue;
            }
            String section = point.section().isBlank() ? "其他内容" : point.section();
            grouped.computeIfAbsent(section, key -> new ArrayList<>()).add(i);
        }

        List<OutlineSection> outline = new ArrayList<>();
        for (PlanSection section : plan.sections()) {
            List<Integer> seqs = grouped.remove(section.title());
            if (seqs != null && !seqs.isEmpty()) {
                outline.add(new OutlineSection(section.title(), seqs));
            }
        }
        grouped.forEach((section, seqs) -> outline.add(new OutlineSection(section, seqs)));
        return outline;
    }

    private static String mechanicalSummary(List<VerifiedPoint> points) {
        StringBuilder summary = new StringBuilder();
        for (VerifiedPoint point : points) {
            if (!point.grounded()) {
                continue;
            }
            if (summary.length() > 0) {
                summary.append('；');
            }
            summary.append(point.text());
            if (summary.length() >= 240) {
                break;
            }
        }
        if (summary.isEmpty()) {
            return "本视频的要点没有通过足够的证据校验，以下内容仅供快速浏览。";
        }
        return summary + "。以上要点均可回跳原片时间点核对。";
    }

    private static String mechanicalMarkdown(String title,
                                             Plan plan,
                                             List<VerifiedPoint> points,
                                             String model,
                                             String summary) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(title).append("\n\n");
        md.append("> 视频笔记 · 由 ").append(model == null ? "模型" : model)
                .append(" 生成 · 证据经后端校验\n\n");
        md.append("## 内容总结\n\n").append(summary).append("\n\n");

        md.append("## 核心观点\n\n");
        for (VerifiedPoint point : points) {
            if (point.status() == PointStatus.REJECTED) {
                continue;
            }
            appendPoint(md, point, "- ");
        }
        md.append("\n");

        md.append("## 章节\n\n");
        int sectionNo = 1;
        for (PlanSection section : plan.sections()) {
            List<VerifiedPoint> sectionPoints = points.stream()
                    .filter(point -> point.status() != PointStatus.REJECTED)
                    .filter(point -> section.title().equals(point.section()))
                    .toList();
            if (sectionPoints.isEmpty()) {
                continue;
            }
            md.append("### ").append(sectionNo++).append(". ").append(section.title()).append("\n\n");
            for (VerifiedPoint point : sectionPoints) {
                appendPoint(md, point, "- ");
            }
            md.append("\n");
        }

        md.append("## 时间轴\n\n");
        for (VerifiedPoint point : points) {
            if (point.status() == PointStatus.REJECTED) {
                continue;
            }
            md.append("- [").append(formatTime(point.startMs())).append("] ")
                    .append(point.text()).append("\n");
        }
        md.append("\n");

        List<VerifiedPoint> rejected = points.stream()
                .filter(point -> point.status() == PointStatus.REJECTED)
                .toList();
        if (!rejected.isEmpty()) {
            md.append("## 未通过校验\n\n");
            for (VerifiedPoint point : rejected) {
                md.append("- ").append(point.text())
                        .append("（").append(point.reason()).append("）\n");
            }
        }
        return md.toString();
    }

    private static void appendPoint(StringBuilder md, VerifiedPoint point, String prefix) {
        md.append(prefix).append(point.text())
                .append(" [").append(formatTime(point.startMs())).append("]");
        if (point.status() == PointStatus.INFERRED) {
            md.append("（模型推断）");
        }
        md.append("\n");
    }

    static String formatTime(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%02d:%02d", minutes, seconds);
    }
}
