package com.zhaozhenbo.video.adapter.caption;

import com.zhaozhenbo.video.config.AsrProperties;
import com.zhaozhenbo.video.config.ExternalToolsProperties;
import com.zhaozhenbo.video.domain.CaptionCue;
import com.zhaozhenbo.video.port.AsrPort;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B 站链接直连公开 API，绕开 yt-dlp 常见 412：
 *   pagelist -> cid
 *   player/v2 -> 字幕列表（有 CC 字幕时）
 *   playurl  -> dash 音频（无字幕且开启 ASR 时，只下音频）
 *
 * 不下载整段视频，避免带宽、存储和版权问题。
 */
public class BilibiliCaptionSource {

    private static final Pattern BV = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private final ExternalToolsProperties tools;
    private final AsrProperties asr;
    private final AsrPort asrPort;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public BilibiliCaptionSource(ExternalToolsProperties tools,
                                 AsrProperties asr,
                                 AsrPort asrPort,
                                 ObjectMapper mapper) {
        this.tools = tools;
        this.asr = asr;
        this.asrPort = asrPort;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public List<CaptionCue> load(String url) {
        Path work = null;
        try {
            work = Files.createTempDirectory("bilibili-caption-");
            String bvid = resolveBvid(url);
            Map<String, Object> page = api("https://api.bilibili.com/x/player/pagelist?bvid="
                    + URLEncoder.encode(bvid, StandardCharsets.UTF_8));
            Map<String, Object> pageData = firstData(page);
            long cid = number(pageData.get("cid"));
            String title = String.valueOf(pageData.getOrDefault("part", bvid));

            Map<String, Object> player = api("https://api.bilibili.com/x/player/v2?bvid="
                    + URLEncoder.encode(bvid, StandardCharsets.UTF_8) + "&cid=" + cid);
            Map<String, Object> subtitle = nested(player, "data", "subtitle");
            List<Map<String, Object>> subtitles = list(subtitle == null ? null : subtitle.get("subtitles"));
            if (!subtitles.isEmpty()) {
                Map<String, Object> chosen = chooseChinese(subtitles);
                String subtitleUrl = String.valueOf(chosen.get("subtitle_url"));
                if (subtitleUrl.startsWith("//")) {
                    subtitleUrl = "https:" + subtitleUrl;
                }
                return parseSubtitle(apiUri(subtitleUrl));
            }

            if (!asr.isEnabled()) {
                throw new IllegalStateException(
                        "视频《" + title + "》没有公开 CC 字幕。请配置 ASR_ENABLED=true 和 ASR_API_KEY 后再试。");
            }
            Path audio = downloadAudio(bvid, cid, work);
            Path mp3 = work.resolve("audio.mp3");
            run(List.of(tools.getFfmpeg(), "-y", "-i", audio.toString(),
                    "-vn", "-ac", "1", "-ar", "16000", "-c:a", "libmp3lame",
                    "-b:a", "48k", mp3.toString()),
                    work.resolve("audio.log"), 300);
            return asrPort.transcribe(mp3);
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("B 站解析失败: " + e.getMessage(), e);
        } finally {
            cleanup(work);
        }
    }

    private String resolveBvid(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com/")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        Matcher matcher = BV.matcher(response.uri().toString());
        if (!matcher.find()) {
            matcher = BV.matcher(response.body());
        }
        if (!matcher.find(0)) {
            throw new IllegalArgumentException("链接里没有识别到 BV 号");
        }
        return matcher.group();
    }

    private Map<String, Object> api(String url) throws Exception {
        return mapper.readValue(apiUri(url), Map.class);
    }

    private String apiUri(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com/")
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("B 站 API HTTP " + response.statusCode());
        }
        return response.body();
    }

    private List<CaptionCue> parseSubtitle(String json) throws Exception {
        Map<String, Object> root = mapper.readValue(json, Map.class);
        List<Map<String, Object>> body = list(root.get("body"));
        List<CaptionCue> cues = new ArrayList<>();
        for (Map<String, Object> item : body) {
            String text = String.valueOf(item.getOrDefault("content", "")).strip();
            if (text.isEmpty()) continue;
            long start = Math.round(doubleValue(item.get("from")) * 1000);
            long end = Math.round(doubleValue(item.get("to")) * 1000);
            cues.add(new CaptionCue(cues.size(), start, Math.max(start, end), text));
        }
        if (cues.isEmpty()) {
            throw new IllegalStateException("B 站字幕文件为空");
        }
        return cues;
    }

    private Path downloadAudio(String bvid, long cid, Path work) throws Exception {
        Map<String, Object> play = api("https://api.bilibili.com/x/player/playurl?bvid="
                + URLEncoder.encode(bvid, StandardCharsets.UTF_8) + "&cid=" + cid
                + "&fnval=16&fourk=0");
        Map<String, Object> dash = nested(play, "data", "dash");
        List<Map<String, Object>> audios = list(dash == null ? null : dash.get("audio"));
        if (audios.isEmpty()) {
            throw new IllegalStateException("B 站没有返回音频流");
        }
        Map<String, Object> audio = audios.stream()
                .max(Comparator.comparingLong(item -> number(item.get("bandwidth"))))
                .orElse(audios.getFirst());
        String baseUrl = String.valueOf(audio.getOrDefault("baseUrl", audio.get("base_url")));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(120))
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com/")
                .GET()
                .build();
        Path target = work.resolve("audio.m4s");
        HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() != 200 || !Files.isRegularFile(target)) {
            throw new IllegalStateException("B 站音频下载失败 HTTP " + response.statusCode());
        }
        return target;
    }

    private void run(List<String> command, Path log, int timeoutSeconds) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("ffmpeg 超时");
        }
        if (process.exitValue() != 0) {
            String output = Files.isRegularFile(log) ? Files.readString(log) : "";
            throw new IllegalStateException("ffmpeg 失败: " + output.substring(Math.max(0, output.length() - 500)));
        }
    }

    private static Map<String, Object> chooseChinese(List<Map<String, Object>> subtitles) {
        return subtitles.stream()
                .filter(item -> String.valueOf(item.get("lan")).startsWith("zh"))
                .findFirst()
                .orElse(subtitles.getFirst());
    }

    private static Map<String, Object> firstData(Map<String, Object> root) {
        Map<String, Object> data = nested(root, "data");
        if (data != null) return data;
        List<Map<String, Object>> list = list(root.get("data"));
        return list.isEmpty() ? Map.of() : list.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> root, String... keys) {
        Object current = root;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(key);
        }
        return current instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return (List<Map<String, Object>>) list;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
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
