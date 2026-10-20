package com.zhaozhenbo.video.adapter.llm;

import com.zhaozhenbo.video.domain.LlmCall;
import com.zhaozhenbo.video.domain.LlmStep;
import com.zhaozhenbo.video.domain.Segment;
import com.zhaozhenbo.video.pipeline.agent.ComposerInput;
import com.zhaozhenbo.video.pipeline.agent.ComposerOutput;
import com.zhaozhenbo.video.pipeline.agent.CriticInput;
import com.zhaozhenbo.video.pipeline.agent.ExtractedPoint;
import com.zhaozhenbo.video.pipeline.agent.ExtractorInput;
import com.zhaozhenbo.video.pipeline.agent.Plan;
import com.zhaozhenbo.video.pipeline.agent.PlanSection;
import com.zhaozhenbo.video.pipeline.agent.PlannerInput;
import com.zhaozhenbo.video.pipeline.agent.Prompts;
import com.zhaozhenbo.video.pipeline.agent.VerifiedPoint;
import com.zhaozhenbo.video.port.LlmException;
import com.zhaozhenbo.video.port.LlmPort;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 离线 Fake：不联网、不烧 token，输出格式与真实模型一致。
 *
 * 它直接读取 LlmCall.input，而不是去解析 prompt。这样 prompt 怎么改，
 * 测试都不受影响；同时又能覆盖“模型返回合法 JSON”的主路径。
 */
public class FakeLlmAdapter implements LlmPort {

    private final ObjectMapper mapper;
    private final Map<LlmStep, AtomicInteger> failures = new EnumMap<>(LlmStep.class);

    public FakeLlmAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
        for (LlmStep step : LlmStep.values()) {
            failures.put(step, new AtomicInteger());
        }
    }

    /** 让下一步指定步骤失败一次 */
    public void failNext(LlmStep step) {
        failures.get(step).incrementAndGet();
    }

    /** 让指定步骤一直失败 */
    public void failAlways(LlmStep step) {
        failures.get(step).set(Integer.MAX_VALUE);
    }

    @Override
    public String complete(LlmCall call) {
        if (consumeFailure(call.step())) {
            throw new LlmException("FakeLlm 模拟失败: " + call.step());
        }
        try {
            return switch (call.step()) {
                case PLANNER -> planner((PlannerInput) call.input());
                case EXTRACTOR -> extractor((ExtractorInput) call.input());
                case CRITIC -> critic((CriticInput) call.input());
                case COMPOSER -> composer((ComposerInput) call.input());
            };
        } catch (Exception e) {
            throw new LlmException("FakeLlm 生成响应失败: " + call.step(), e);
        }
    }

    private boolean consumeFailure(LlmStep step) {
        AtomicInteger remaining = failures.get(step);
        while (true) {
            int current = remaining.get();
            if (current <= 0) {
                return false;
            }
            if (remaining.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

    private String planner(PlannerInput input) throws Exception {
        List<Segment> segments = input.segments();
        List<PlanSection> sections = new ArrayList<>();
        int size = 3;
        for (int from = 0; from < segments.size(); from += size) {
            int to = Math.min(segments.size(), from + size);
            List<Integer> indexes = new ArrayList<>();
            for (int i = from; i < to; i++) {
                indexes.add(segments.get(i).index());
            }
            sections.add(new PlanSection("第 " + (sections.size() + 1) + " 部分", indexes));
        }
        return mapper.writeValueAsString(new Plan(sections));
    }

    private String extractor(ExtractorInput input) throws Exception {
        List<ExtractedPoint> points = new ArrayList<>();
        for (Segment segment : input.segments()) {
            String quote = firstSentence(segment.text());
            String text = quote.length() <= 60 ? quote : quote.substring(0, 60);
            points.add(new ExtractedPoint(text, quote, segment.startMs(), segment.endMs(),
                    input.sectionTitle()));
        }
        return mapper.writeValueAsString(Map.of("points", points));
    }

    private String critic(CriticInput input) throws Exception {
        Set<Integer> consistent = new LinkedHashSet<>();
        for (int i = 0; i < input.points().size(); i++) {
            consistent.add(i);
        }
        return mapper.writeValueAsString(Map.of("consistent", consistent));
    }

    private String composer(ComposerInput input) throws Exception {
        StringBuilder summary = new StringBuilder();
        for (VerifiedPoint point : input.points()) {
            if (!point.grounded()) {
                continue;
            }
            if (summary.length() > 0) {
                summary.append('；');
            }
            summary.append(point.text());
            if (summary.length() >= 180) {
                break;
            }
        }
        // v13 的 gate 要求 summary 用固定句式收口，离线的假模型也必须遵守，
        // 否则测试覆盖到的是机械模板而不是模型路径
        summary.append("。核心结论可以概括为：**以上要点均可回跳原片时间点核对**");

        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(input.title()).append("\n\n");
        markdown.append("## 内容总结\n\n").append(summary).append("\n\n");
        markdown.append("## 核心观点\n\n");
        for (VerifiedPoint point : input.points()) {
            if (point.status() == com.zhaozhenbo.video.pipeline.agent.PointStatus.REJECTED) {
                continue;
            }
            markdown.append("- ").append(point.text());
            if (point.status() == com.zhaozhenbo.video.pipeline.agent.PointStatus.INFERRED) {
                markdown.append("（模型推断）");
            }
            markdown.append("\n");
        }
        markdown.append("\n## 章节\n\n");
        int sectionNo = 1;
        for (PlanSection section : input.plan().sections()) {
            List<VerifiedPoint> sectionPoints = input.points().stream()
                    .filter(point -> point.status() != com.zhaozhenbo.video.pipeline.agent.PointStatus.REJECTED)
                    .filter(point -> section.title().equals(point.section()))
                    .toList();
            if (sectionPoints.isEmpty()) {
                continue;
            }
            markdown.append("### ").append(sectionNo++).append(". ")
                    .append(section.title()).append("\n\n");
            for (VerifiedPoint point : sectionPoints) {
                markdown.append("- ").append(point.text()).append("\n");
            }
            markdown.append("\n");
        }
        markdown.append("## 时间轴\n\n");
        for (VerifiedPoint point : input.points()) {
            if (point.status() == com.zhaozhenbo.video.pipeline.agent.PointStatus.REJECTED) {
                continue;
            }
            markdown.append("- [").append(time(point.startMs())).append("] ")
                    .append(point.text()).append("\n");
        }
        return mapper.writeValueAsString(new ComposerOutput(summary.toString(), markdown.toString()));
    }

    private static String time(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%02d:%02d", minutes, seconds);
    }

    private static String firstSentence(String text) {
        String cleaned = text.strip();
        for (int i = 0; i < cleaned.length(); i++) {
            if ("。！？!?；;".indexOf(cleaned.charAt(i)) >= 0) {
                return cleaned.substring(0, i + 1);
            }
        }
        return cleaned.length() <= 120 ? cleaned : cleaned.substring(0, 120);
    }
}
