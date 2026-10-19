package com.zhaozhenbo.video.adapter.asr;

import com.zhaozhenbo.video.config.AsrProperties;
import com.zhaozhenbo.video.config.ExternalToolsProperties;
import com.zhaozhenbo.video.domain.CaptionCue;
import com.zhaozhenbo.video.port.AsrPort;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 硅基流动 SenseVoice 适配器。
 *
 * 它的转写接口返回纯文本，没有句级 segments；为了保住证据时间轴，
 * 服务端按 60 秒切片，再用“切片开始时间”作为这一段 cue 的起点。
 * 精度约 ±60 秒，适合先跑通多模态产品，不是逐句对齐的替代品。
 */
public class SiliconFlowAsrAdapter implements AsrPort {

    private static final Logger log = LoggerFactory.getLogger(SiliconFlowAsrAdapter.class);

    /** 同时打几个转写请求：单片约 20~30 秒，串行跑长视频会等到怀疑人生。 */
    private static final int CONCURRENCY = 3;

    private final AsrProperties properties;
    private final ExternalToolsProperties tools;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public SiliconFlowAsrAdapter(AsrProperties properties,
                                 ExternalToolsProperties tools,
                                 ObjectMapper mapper) {
        this.properties = properties;
        this.tools = tools;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public List<CaptionCue> transcribe(Path audioFile) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("未启用云 ASR");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("ASR_API_KEY 未配置");
        }

        Path work = null;
        try {
            work = Files.createTempDirectory("siliconflow-asr-");
            long totalMs = durationMs(audioFile);
            List<Path> chunks = split(audioFile, work);
            List<CaptionCue> cues = transcribeAll(chunks, totalMs);
            if (cues.isEmpty()) {
                throw new IllegalStateException("SiliconFlow 没有返回可用文本");
            }
            return cues;
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("SiliconFlow 转写失败: " + e.getMessage(), e);
        } finally {
            cleanup(work);
        }
    }

    /**
     * 并发转写，但按分片原始顺序组装结果 —— 时间轴不能被完成顺序打乱。
     *
     * futures 是按 chunk 顺序提交的，所以按下标 get() 拿到的文本天然对应
     * "第 i 段 60 秒"，哪怕第 3 片比第 1 片先返回。
     */
    private List<CaptionCue> transcribeAll(List<Path> chunks, long totalMs) throws Exception {
        if (chunks.isEmpty()) {
            return List.of();
        }
        AtomicInteger finished = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(CONCURRENCY, chunks.size()));
        try {
            List<Future<String>> futures = new ArrayList<>(chunks.size());
            for (Path chunk : chunks) {
                futures.add(pool.submit(() -> {
                    String text = transcribeChunk(chunk);
                    int done = finished.incrementAndGet();
                    log.info("ASR 分片 {}/{} 完成", done, chunks.size());
                    return text;
                }));
            }

            long stepMs = properties.getChunkSeconds() * 1000L;
            List<CaptionCue> cues = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                String text = futures.get(i).get();
                if (text == null || text.isBlank()) {
                    continue;
                }
                long startMs = i * stepMs;
                long endMs = totalMs > 0 ? Math.min(totalMs, startMs + stepMs) : startMs + stepMs;
                cues.add(new CaptionCue(cues.size(), startMs, Math.max(startMs, endMs), text.strip()));
            }
            return cues;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<Path> split(Path audio, Path work) throws Exception {
        Path log = work.resolve("split.log");
        Process process = new ProcessBuilder(
                tools.getFfmpeg(),
                "-y", "-i", audio.toString(),
                "-f", "segment",
                "-segment_time", String.valueOf(properties.getChunkSeconds()),
                "-c:a", "libmp3lame", "-b:a", "48k",
                work.resolve("chunk-%03d.mp3").toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("音频切片超时");
        }
        if (process.exitValue() != 0) {
            String output = Files.isRegularFile(log) ? Files.readString(log) : "";
            throw new IllegalStateException("音频切片失败: " + tail(output));
        }
        try (var stream = Files.list(work)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".mp3"))
                    .sorted()
                    .toList();
        }
    }

    private long durationMs(Path audio) {
        try {
            Path log = Files.createTempFile("asr-duration-", ".log");
            Process process = new ProcessBuilder(
                    tools.getFfprobe(),
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    audio.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return 0;
            }
            String value = Files.readString(log).strip();
            return Math.round(Double.parseDouble(value) * 1000);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String transcribeChunk(Path chunk) throws Exception {
        String boundary = "----video-insight-" + UUID.randomUUID();
        byte[] audio = Files.readAllBytes(chunk);
        byte[] body = multipart(boundary, chunk.getFileName().toString(), audio);
        HttpRequest request = HttpRequest.newBuilder(endpoint())
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 403) {
            throw new IllegalStateException("ASR 403 Forbidden：检查 API Key、模型权限或地区限制");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("ASR HTTP " + response.statusCode() + ": "
                    + tail(response.body()));
        }

        Map<String, Object> root = mapper.readValue(response.body(), Map.class);
        Object text = root.get("text");
        if (text == null && root.get("result") != null) {
            text = root.get("result");
        }
        return text == null ? "" : String.valueOf(text);
    }

    private byte[] multipart(String boundary, String filename, byte[] audio) {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
                + properties.getModel() + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: audio/mpeg\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
        byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[headBytes.length + audio.length + tailBytes.length];
        System.arraycopy(headBytes, 0, body, 0, headBytes.length);
        System.arraycopy(audio, 0, body, headBytes.length, audio.length);
        System.arraycopy(tailBytes, 0, body, headBytes.length + audio.length, tailBytes.length);
        return body;
    }

    private URI endpoint() {
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        return URI.create(base.endsWith("/v1") ? base + "/audio/transcriptions"
                : base + "/v1/audio/transcriptions");
    }

    private static String tail(String text) {
        return text.length() <= 500 ? text : text.substring(text.length() - 500);
    }

    private static void cleanup(Path dir) {
        if (dir == null) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // ignore
                }
            });
        } catch (Exception ignored) {
            // ignore
        }
    }
}
