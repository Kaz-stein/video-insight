package com.zhaozhenbo.video.pipeline.agent;

import com.zhaozhenbo.video.domain.LlmCall;
import com.zhaozhenbo.video.domain.LlmStep;
import com.zhaozhenbo.video.domain.Segment;
import com.zhaozhenbo.video.port.LlmPort;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 第二步：按章节抽要点。
 *
 * 分章节调用而不是整篇一次性调用：
 *   - prompt 小，单次失败不会毁掉整篇；
 *   - 章节归属由 Planner 决定，Extractor 不需要重复判断结构。
 */
public class Extractor {

    private static final int MAX_POINTS_PER_SECTION = 6;

    private final LlmPort llm;
    private final ObjectMapper mapper;

    public Extractor(LlmPort llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    public ExtractionResult extract(Plan plan, List<Segment> segments) {
        Map<Integer, Segment> byIndex = new HashMap<>();
        for (Segment segment : segments) {
            byIndex.put(segment.index(), segment);
        }

        List<ExtractedPoint> points = new ArrayList<>();
        List<String> failedSections = new ArrayList<>();

        for (PlanSection section : plan.sections()) {
            List<Segment> sectionSegments = section.segmentIndexes().stream()
                    .map(byIndex::get)
                    .filter(segment -> segment != null)
                    .toList();
            if (sectionSegments.isEmpty()) {
                continue;
            }
            try {
                String segmentsJson = mapper.writeValueAsString(sectionSegments);
                String response = llm.complete(new LlmCall(
                        LlmStep.EXTRACTOR,
                        Prompts.EXTRACTOR_VERSION,
                        Prompts.extractorSystem(),
                        Prompts.extractorUser(section.title(), segmentsJson),
                        new ExtractorInput(section.title(), sectionSegments),
                        0.1,
                        2500));
                ExtractResponse parsed = mapper.readValue(response, ExtractResponse.class);
                List<ExtractedPoint> sectionPoints = parsed.points().stream()
                        .limit(MAX_POINTS_PER_SECTION)
                        .map(point -> point.section().isBlank()
                                ? new ExtractedPoint(point.text(), point.quote(),
                                        point.startMs(), point.endMs(), section.title())
                                : point)
                        .toList();
                if (sectionPoints.isEmpty()) {
                    failedSections.add(section.title());
                } else {
                    points.addAll(sectionPoints);
                }
            } catch (Exception e) {
                failedSections.add(section.title());
            }
        }

        if (points.isEmpty()) {
            throw new AgentException("Extractor 所有章节都失败，无法生成笔记");
        }
        return new ExtractionResult(points, failedSections);
    }
}
