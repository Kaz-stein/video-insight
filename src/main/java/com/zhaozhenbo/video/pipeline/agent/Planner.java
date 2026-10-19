package com.zhaozhenbo.video.pipeline.agent;

import com.zhaozhenbo.video.domain.LlmCall;
import com.zhaozhenbo.video.domain.LlmStep;
import com.zhaozhenbo.video.domain.Segment;
import com.zhaozhenbo.video.port.LlmPort;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第一步：把段落规划成章节。
 *
 * 失败降级：按每 5 段切一个章节。结构会变平，但笔记仍能生成。
 */
public class Planner {

    private static final int FALLBACK_SECTION_SIZE = 5;

    private final LlmPort llm;
    private final ObjectMapper mapper;

    public Planner(LlmPort llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    public Plan plan(List<Segment> segments) {
        if (segments == null || segments.isEmpty()) {
            return new Plan(List.of());
        }
        try {
            String segmentsJson = mapper.writeValueAsString(segments);
            String response = llm.complete(new LlmCall(
                    LlmStep.PLANNER,
                    Prompts.PLANNER_VERSION,
                    Prompts.plannerSystem(),
                    Prompts.plannerUser(segmentsJson),
                    new PlannerInput(segments),
                    0.2,
                    1500));
            return normalize(mapper.readValue(response, Plan.class), segments);
        } catch (Exception e) {
            return fallback(segments);
        }
    }

    /**
     * 模型输出不可信：去掉不存在的段落、重复分配的段落，把漏掉的段落补到“补充内容”。
     */
    private Plan normalize(Plan raw, List<Segment> segments) {
        Map<Integer, Segment> valid = new LinkedHashMap<>();
        for (Segment segment : segments) {
            valid.put(segment.index(), segment);
        }

        List<PlanSection> sections = new ArrayList<>();
        Map<Integer, Boolean> used = new LinkedHashMap<>();
        for (PlanSection section : raw.sections()) {
            List<Integer> indexes = new ArrayList<>();
            for (Integer index : section.segmentIndexes()) {
                if (index != null && valid.containsKey(index) && !used.containsKey(index)) {
                    used.put(index, true);
                    indexes.add(index);
                }
            }
            if (!indexes.isEmpty()) {
                sections.add(new PlanSection(section.title(), indexes));
            }
        }

        List<Integer> missing = new ArrayList<>();
        for (Integer index : valid.keySet()) {
            if (!used.containsKey(index)) {
                missing.add(index);
            }
        }
        if (!missing.isEmpty()) {
            sections.add(new PlanSection("补充内容", missing));
        }
        return sections.isEmpty() ? fallback(segments) : new Plan(sections);
    }

    private Plan fallback(List<Segment> segments) {
        List<PlanSection> sections = new ArrayList<>();
        for (int from = 0; from < segments.size(); from += FALLBACK_SECTION_SIZE) {
            int to = Math.min(segments.size(), from + FALLBACK_SECTION_SIZE);
            List<Integer> indexes = new ArrayList<>();
            for (int i = from; i < to; i++) {
                indexes.add(segments.get(i).index());
            }
            sections.add(new PlanSection("第 " + (sections.size() + 1) + " 部分", indexes));
        }
        return new Plan(sections);
    }
}
