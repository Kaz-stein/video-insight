package com.zhaozhenbo.video.pipeline.agent;

import com.zhaozhenbo.video.domain.LlmCall;
import com.zhaozhenbo.video.domain.LlmStep;
import com.zhaozhenbo.video.port.LlmPort;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 第三步：语义审查。
 *
 * 3-gram 只能证明“字面像”，不能证明“这句话真的支持这个观点”。
 * Critic 负责第二层判断；它失败时全部降为 INFERRED。
 */
public class Critic {

    private final LlmPort llm;
    private final ObjectMapper mapper;

    public Critic(LlmPort llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    public CriticResult review(List<ExtractedPoint> points) {
        if (points == null || points.isEmpty()) {
            return new CriticResult(true, Set.of());
        }
        try {
            String pointsJson = mapper.writeValueAsString(points);
            String response = llm.complete(new LlmCall(
                    LlmStep.CRITIC,
                    Prompts.CRITIC_VERSION,
                    Prompts.criticSystem(),
                    Prompts.criticUser(pointsJson),
                    new CriticInput(points),
                    0.0,
                    1000));
            CriticResponse parsed = mapper.readValue(response, CriticResponse.class);
            Set<Integer> consistent = new LinkedHashSet<>();
            for (Integer index : parsed.consistent()) {
                if (index != null && index >= 0 && index < points.size()) {
                    consistent.add(index);
                }
            }
            return new CriticResult(true, consistent);
        } catch (Exception e) {
            return CriticResult.unavailable();
        }
    }
}
