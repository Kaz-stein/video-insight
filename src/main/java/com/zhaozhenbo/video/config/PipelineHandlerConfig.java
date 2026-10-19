package com.zhaozhenbo.video.config;

import com.zhaozhenbo.video.adapter.caption.LocalCaptionSource;
import com.zhaozhenbo.video.adapter.caption.DefaultCaptionResolver;
import com.zhaozhenbo.video.adapter.caption.LinkCaptionSource;
import com.zhaozhenbo.video.adapter.caption.VideoCaptionSource;
import com.zhaozhenbo.video.adapter.caption.BilibiliCaptionSource;
import com.zhaozhenbo.video.adapter.asr.OpenAiAsrAdapter;
import com.zhaozhenbo.video.adapter.asr.SiliconFlowAsrAdapter;
import com.zhaozhenbo.video.adapter.llm.FakeLlmAdapter;
import com.zhaozhenbo.video.adapter.llm.HttpLlmAdapter;
import com.zhaozhenbo.video.adapter.llm.LlmProperties;
import com.zhaozhenbo.video.application.TaskContext;
import com.zhaozhenbo.video.pipeline.EvidenceVerifier;
import com.zhaozhenbo.video.pipeline.Segmenter;
import com.zhaozhenbo.video.pipeline.agent.Composer;
import com.zhaozhenbo.video.pipeline.agent.Critic;
import com.zhaozhenbo.video.pipeline.agent.Extractor;
import com.zhaozhenbo.video.pipeline.agent.Planner;
import com.zhaozhenbo.video.pipeline.stage.ComposingStage;
import com.zhaozhenbo.video.pipeline.stage.ExtractingStage;
import com.zhaozhenbo.video.pipeline.stage.FetchingStage;
import com.zhaozhenbo.video.pipeline.stage.PlanningStage;
import com.zhaozhenbo.video.pipeline.stage.SegmentingStage;
import com.zhaozhenbo.video.pipeline.stage.VerifyingStage;
import com.zhaozhenbo.video.port.ArtifactStorePort;
import com.zhaozhenbo.video.port.AsrPort;
import com.zhaozhenbo.video.port.LlmPort;
import com.zhaozhenbo.video.port.NoteStorePort;
import com.zhaozhenbo.video.port.StageHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 真实流水线装配。测试 profile 关闭，避免与测试里的 fake handlers 重复注册。
 */
@Configuration
@ConditionalOnProperty(name = "video.pipeline.real-handlers", havingValue = "true")
@EnableConfigurationProperties({ExternalToolsProperties.class, AsrProperties.class})
public class PipelineHandlerConfig {

    @Bean
    public AsrPort asrPort(AsrProperties properties,
                           ExternalToolsProperties tools,
                           ObjectMapper mapper) {
        if ("siliconflow".equalsIgnoreCase(properties.getProvider())) {
            return new SiliconFlowAsrAdapter(properties, tools, mapper);
        }
        return new OpenAiAsrAdapter(properties, mapper);
    }

    @Bean
    public DefaultCaptionResolver captionResolver(ExternalToolsProperties tools,
                                                  AsrProperties asrProperties,
                                                  AsrPort asrPort,
                                                  ObjectMapper mapper) {
        return new DefaultCaptionResolver(
                new LocalCaptionSource(),
                new VideoCaptionSource(tools, asrProperties, asrPort),
                new LinkCaptionSource(tools, asrProperties, asrPort),
                new BilibiliCaptionSource(tools, asrProperties, asrPort, mapper));
    }

    @Bean
    public LlmPort llmPort(LlmProperties properties, ObjectMapper mapper) {
        if ("fake".equalsIgnoreCase(properties.getProvider())) {
            return new FakeLlmAdapter(mapper);
        }
        return new HttpLlmAdapter(properties, mapper);
    }

    @Bean
    public Planner planner(LlmPort llm, ObjectMapper mapper) {
        return new Planner(llm, mapper);
    }

    @Bean
    public Extractor extractor(LlmPort llm, ObjectMapper mapper) {
        return new Extractor(llm, mapper);
    }

    @Bean
    public Critic critic(LlmPort llm, ObjectMapper mapper) {
        return new Critic(llm, mapper);
    }

    @Bean
    public Composer composer(LlmPort llm, ObjectMapper mapper) {
        return new Composer(llm, mapper);
    }

    @Bean
    public EvidenceVerifier evidenceVerifier() {
        return new EvidenceVerifier();
    }

    @Bean
    public Segmenter segmenter() {
        return new Segmenter();
    }

    @Bean
    public StageHandler fetchingStage(DefaultCaptionResolver captions, ArtifactStorePort artifacts) {
        return new FetchingStage(captions, artifacts);
    }

    @Bean
    public StageHandler segmentingStage(Segmenter segmenter, ArtifactStorePort artifacts) {
        return new SegmentingStage(segmenter, artifacts);
    }

    @Bean
    public StageHandler planningStage(Planner planner, ArtifactStorePort artifacts) {
        return new PlanningStage(planner, artifacts);
    }

    @Bean
    public StageHandler extractingStage(Extractor extractor, ArtifactStorePort artifacts) {
        return new ExtractingStage(extractor, artifacts);
    }

    @Bean
    public StageHandler verifyingStage(Critic critic, EvidenceVerifier verifier, ArtifactStorePort artifacts) {
        return new VerifyingStage(critic, verifier, artifacts);
    }

    @Bean
    public StageHandler composingStage(Composer composer,
                                       ArtifactStorePort artifacts,
                                       NoteStorePort notes,
                                       LlmProperties properties) {
        return new ComposingStage(composer, artifacts, notes, modelName(properties));
    }

    /**
     * provider=fake 时不要冒充真实模型名（否则笔记里写着 deepseek-chat，
     * 实际内容只是抄原句）。前端据此打出"离线抽取模式"提示。
     */
    private static String modelName(LlmProperties properties) {
        return "fake".equalsIgnoreCase(properties.getProvider())
                ? "offline-fake"
                : properties.getModel();
    }
}
