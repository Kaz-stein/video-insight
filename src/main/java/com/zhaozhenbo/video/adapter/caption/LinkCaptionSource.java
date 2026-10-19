package com.zhaozhenbo.video.adapter.caption;

import com.zhaozhenbo.video.config.ExternalToolsProperties;
import com.zhaozhenbo.video.config.AsrProperties;
import com.zhaozhenbo.video.domain.CaptionCue;
import com.zhaozhenbo.video.port.AsrPort;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 视频链接 → 字幕。
 *
 * 用 yt-dlp 只下载字幕，不下载视频文件（--skip-download）。
 * B 站触发 412 时需要在 YTDLP_COOKIES_FILE 配置一份 cookies.txt。
 */
public class LinkCaptionSource {

    private final ExternalToolsProperties tools;
    private final AsrProperties asr;
    private final AsrPort asrPort;
    private final CaptionParser parser = new CaptionParser();

    public LinkCaptionSource(ExternalToolsProperties tools, AsrProperties asr, AsrPort asrPort) {
        this.tools = tools;
        this.asr = asr;
        this.asrPort = asrPort;
    }

    public List<CaptionCue> load(String url) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (Exception e) {
            throw new IllegalArgumentException("视频链接格式不正确");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = host.endsWith("bilibili.com") || host.equals("b23.tv")
                || host.endsWith("youtube.com") || host.equals("youtu.be");
        if (!allowed || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("v1 只支持 B 站 / YouTube 的 http(s) 链接");
        }

        Path work = null;
        try {
            work = Files.createTempDirectory("link-caption-");
            List<String> command = new ArrayList<>(List.of(
                    tools.getYtdlp(),
                    "--skip-download",
                    "--no-playlist",
                    "--write-subs",
                    "--write-auto-subs",
                    "--sub-langs", "zh-Hans,zh-CN,zh,en",
                    "--sub-format", "vtt",
                    "-o", work.resolve("%(id)s.%(ext)s").toString()));
            if (tools.getCookiesFile() != null && !tools.getCookiesFile().isBlank()) {
                command.add("--cookies");
                command.add(tools.getCookiesFile());
            }
            command.add(uri.toString());

            Path log = work.resolve("ytdlp.log");
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("链接解析超时");
            }
            if (process.exitValue() != 0) {
                String output = Files.isRegularFile(log) ? Files.readString(log) : "";
                throw new IllegalStateException("链接解析失败: " + tail(output));
            }

            List<Path> candidates;
            try (var stream = Files.list(work)) {
                candidates = stream.filter(path -> {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.endsWith(".vtt") || name.endsWith(".srt");
                }).sorted().toList();
            }
            if (candidates.isEmpty()) {
                String output = Files.isRegularFile(log) ? Files.readString(log) : "";
                if (asr.isEnabled()) {
                    Path audio = downloadAudio(uri, work);
                    return asrPort.transcribe(audio);
                }
                throw new IllegalStateException(
                        "链接没有可用字幕，且未启用云 ASR。B 站 412 时请配置 YTDLP_COOKIES_FILE。\n" + tail(output));
            }
            return parser.parse(Files.readString(candidates.getFirst(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
                throw (RuntimeException) e;
            }
            throw new IllegalStateException("链接解析失败: " + e.getMessage(), e);
        } finally {
            cleanup(work);
        }
    }

    /** 链接没有字幕时只下载音频，不下载整段视频，再交给 ASR。 */
    private Path downloadAudio(URI uri, Path work) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                tools.getYtdlp(),
                "--no-playlist",
                "-f", "bestaudio/best",
                "--extract-audio",
                "--audio-format", "wav",
                "--audio-quality", "5",
                "--ffmpeg-location", tools.getFfmpeg(),
                "-o", work.resolve("audio.%(ext)s").toString()));
        if (tools.getCookiesFile() != null && !tools.getCookiesFile().isBlank()) {
            command.add("--cookies");
            command.add(tools.getCookiesFile());
        }
        command.add(uri.toString());

        Path log = work.resolve("ytdlp-audio.log");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("链接音频下载超时");
        }
        if (process.exitValue() != 0) {
            String output = Files.isRegularFile(log) ? Files.readString(log) : "";
            throw new IllegalStateException("链接音频下载失败: " + tail(output));
        }
        try (var stream = Files.list(work)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".wav"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("yt-dlp 没有生成音频文件"));
        }
    }

    private static String tail(String output) {
        return output.length() <= 800 ? output : output.substring(output.length() - 800);
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
