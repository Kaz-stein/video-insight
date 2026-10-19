package com.zhaozhenbo.video.adapter.asr;

import com.zhaozhenbo.video.config.AsrProperties;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI 兼容的 Whisper 转写适配器。
 *
 * 长音频应优先用厂商的异步任务接口；v1 用同步 multipart 接口，
 * 通过 maxAudioMb 限制输入，避免一次把超大文件读进内存。
 */
public class OpenAiAsrAdapter implements AsrPort {

    private final AsrProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OpenAiAsrAdapter(AsrProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public List<CaptionCue> transcribe(Path audioFile) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("未启用云 ASR，无法转写没有字幕的视频");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException("ASR_API_KEY 未配置");
        }
        try {
            long size = Files.size(audioFile);
            if (size > properties.getMaxAudioMb() * 1024L * 1024L) {
                throw new IllegalStateException("音频超过 ASR 单次限制: " + properties.getMaxAudioMb() + " MB");
            }
            String boundary = "----video-insight-" + UUID.randomUUID();
            byte[] audio = Files.readAllBytes(audioFile);
            byte[] body = multipart(boundary, audioFile.getFileName().toString(), audio);

            HttpRequest request = HttpRequest.newBuilder(endpoint())
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("ASR HTTP " + response.statusCode() + ": "
                        + response.body().substring(0, Math.min(500, response.body().length())));
            }
            return parse(response.body());
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("ASR 调用失败: " + e.getMessage(), e);
        }
    }

    private byte[] multipart(String boundary, String filename, byte[] audio) {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
                + properties.getModel() + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"language\"\r\n\r\n"
                + properties.getLanguage() + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"response_format\"\r\n\r\n"
                + "verbose_json\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
        byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[headBytes.length + audio.length + tailBytes.length];
        System.arraycopy(headBytes, 0, body, 0, headBytes.length);
        System.arraycopy(audio, 0, body, headBytes.length, audio.length);
        System.arraycopy(tailBytes, 0, body, headBytes.length + audio.length, tailBytes.length);
        return body;
    }

    @SuppressWarnings("unchecked")
    private List<CaptionCue> parse(String body) throws Exception {
        Map<String, Object> root = mapper.readValue(body, Map.class);
        List<Map<String, Object>> segments = (List<Map<String, Object>>) root.get("segments");
        List<CaptionCue> cues = new ArrayList<>();
        if (segments != null) {
            for (Map<String, Object> segment : segments) {
                String text = String.valueOf(segment.getOrDefault("text", "")).strip();
                if (text.isEmpty()) continue;
                long start = Math.round(number(segment.get("start")) * 1000);
                long end = Math.round(number(segment.get("end")) * 1000);
                cues.add(new CaptionCue(cues.size(), start, Math.max(start, end), text));
            }
        }
        if (cues.isEmpty() && root.get("text") != null) {
            cues.add(new CaptionCue(0, 0, 0, String.valueOf(root.get("text"))));
        }
        if (cues.isEmpty()) {
            throw new IllegalStateException("ASR 返回没有可用字幕");
        }
        return cues;
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private URI endpoint() {
        String base = properties.getBaseUrl().replaceAll("/+$", "");
        return URI.create(base.endsWith("/v1") ? base + "/audio/transcriptions"
                : base + "/v1/audio/transcriptions");
    }
}
