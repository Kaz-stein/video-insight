package com.zhaozhenbo.video.pipeline;

import com.zhaozhenbo.video.adapter.caption.LocalCaptionSource;
import com.zhaozhenbo.video.adapter.llm.FakeLlmAdapter;
import com.zhaozhenbo.video.application.TaskContext;
import com.zhaozhenbo.video.domain.ArtifactKind;
import com.zhaozhenbo.video.pipeline.agent.Composer;
import com.zhaozhenbo.video.pipeline.agent.Critic;
import com.zhaozhenbo.video.pipeline.agent.Extractor;
import com.zhaozhenbo.video.pipeline.agent.NoteDraft;
import com.zhaozhenbo.video.pipeline.agent.Planner;
import com.zhaozhenbo.video.pipeline.stage.ComposingStage;
import com.zhaozhenbo.video.pipeline.stage.ExtractingStage;
import com.zhaozhenbo.video.pipeline.stage.FetchingStage;
import com.zhaozhenbo.video.pipeline.stage.PlanningStage;
import com.zhaozhenbo.video.pipeline.stage.SegmentingStage;
import com.zhaozhenbo.video.pipeline.stage.VerifyingStage;
import com.zhaozhenbo.video.port.ArtifactStorePort;
import com.zhaozhenbo.video.port.NoteStorePort;
import com.zhaozhenbo.video.port.StoredNote;
import com.zhaozhenbo.video.port.StageHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 六个阶段串起来跑一遍：SRT 文件 -> 切段 -> 规划 -> 抽取 -> 校验 -> 成稿。
 * 不启动 Spring，所有外部依赖（LLM、artifact）都换成内存实现。
 */
class PipelineChainTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("SRT 文件走完六阶段，产出可回溯的 NoteDraft")
    void runsAllStages(@TempDir Path tempDir) throws Exception {
        Path srt = tempDir.resolve("demo.srt");
        Files.writeString(srt, """
                1
                00:00:00,000 --> 00:00:08,000
                Java 线程池的核心参数包括核心线程数、最大线程数和队列长度。

                2
                00:00:08,000 --> 00:00:16,000
                当任务数量超过核心线程数时，任务会先进入阻塞队列等待。

                3
                00:00:16,000 --> 00:00:24,000
                只有队列满了，线程池才会创建非核心线程。
                """);

        InMemoryArtifactStore artifacts = new InMemoryArtifactStore(mapper);
        FakeLlmAdapter llm = new FakeLlmAdapter(mapper);
        Segmenter segmenter = new Segmenter();
        Planner planner = new Planner(llm, mapper);
        Extractor extractor = new Extractor(llm, mapper);
        Critic critic = new Critic(llm, mapper);
        Composer composer = new Composer(llm, mapper);
        EvidenceVerifier verifier = new EvidenceVerifier();
        InMemoryNoteStore notes = new InMemoryNoteStore();

        List<StageHandler> stages = List.of(
                new FetchingStage(new LocalCaptionSource(), artifacts),
                new SegmentingStage(segmenter, artifacts),
                new PlanningStage(planner, artifacts),
                new ExtractingStage(extractor, artifacts),
                new VerifyingStage(critic, verifier, artifacts),
                new ComposingStage(composer, artifacts, notes, "fake-model"));

        TaskContext context = new TaskContext(
                1, 1, "f".repeat(64), "FILE", srt.toString(), "线程池讲解", 24_000L);
        for (StageHandler stage : stages) {
            stage.execute(context);
        }

        NoteDraft note = artifacts.load("f".repeat(64), ArtifactKind.NOTE, NoteDraft.class).orElseThrow();

        assertThat(note.title()).isEqualTo("线程池讲解");
        assertThat(note.groundedRatio()).isEqualTo(1.0);
        assertThat(note.passesQualityGate()).isTrue();
        assertThat(note.markdown()).contains("## 内容总结", "## 时间轴", "00:00");
        assertThat(note.outline()).isNotEmpty();
        assertThat(notes.saved).isNotNull();
        assertThat(artifacts.load("f".repeat(64), ArtifactKind.VERIFY, VerificationArtifact.class))
                .isPresent();
    }

    static class InMemoryArtifactStore implements ArtifactStorePort {
        private final ObjectMapper mapper;
        private final Map<String, String> values = new HashMap<>();

        InMemoryArtifactStore(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public <T> Optional<T> load(String fingerprint, ArtifactKind kind, Class<T> type) {
            String json = values.get(fingerprint + "|" + kind.stageKey());
            if (json == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(mapper.readValue(json, type));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void save(String fingerprint, ArtifactKind kind, Object payload) {
            try {
                values.put(fingerprint + "|" + kind.stageKey(), mapper.writeValueAsString(payload));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static class InMemoryNoteStore implements NoteStorePort {
        private NoteDraft saved;

        @Override
        public void save(long userId, String fingerprint, long taskId, NoteDraft draft) {
            saved = draft;
        }

        @Override
        public java.util.Optional<StoredNote> findByTask(long taskId, long userId, String fingerprint) {
            return java.util.Optional.empty();
        }
    }
}
