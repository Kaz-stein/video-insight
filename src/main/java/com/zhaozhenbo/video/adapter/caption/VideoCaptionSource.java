package com.zhaozhenbo.video.adapter.caption;

import com.zhaozhenbo.video.config.AsrProperties;
import com.zhaozhenbo.video.config.ExternalToolsProperties;
import com.zhaozhenbo.video.domain.CaptionCue;
import com.zhaozhenbo.video.port.AsrPort;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 视频文件 → 字幕：
 *   1. 优先抽视频内嵌字幕流（mp4/mkv 常见）；
 *   2. 没有字幕时，抽 16k 单声道 wav，调用云 ASR；
 *   3. 两者都没有就明确失败，不生成空笔记。
 */
public class VideoCaptionSource {

    private final ExternalToolsProperties tools;
    private final AsrProperties asr;
    private final AsrPort asrPort;
    private final CaptionParser parser = new CaptionParser();

    public VideoCaptionSource(ExternalToolsProperties tools, AsrProperties asr, AsrPort asrPort) {
        this.tools = tools;
        this.asr = asr;
        this.asrPort = asrPort;
    }

    public List<CaptionCue> load(String videoRef) {
        Path video = Path.of(videoRef);
        if (!Files.isRegularFile(video)) {
            throw new IllegalArgumentException("视频文件不存在: " + videoRef);
        }
        Path work = null;
        try {
            work = Files.createTempDirectory("video-caption-");
            Path subtitle = work.resolve("embedded.srt");
            run(List.of(tools.getFfmpeg(), "-y", "-i", video.toString(),
                    "-map", "0:s:0", "-c:s", "srt", subtitle.toString()),
                    work.resolve("subtitle.log"), 120);
            if (Files.isRegularFile(subtitle) && Files.size(subtitle) > 0) {
                return parser.parse(Files.readString(subtitle, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // 没有内嵌字幕流是正常情况，继续尝试 ASR
        }

        if (!asr.isEnabled()) {
            cleanup(work);
            throw new IllegalStateException(
                    "视频没有内嵌字幕，且未启用云 ASR。请设置 ASR_ENABLED=true 和 ASR_API_KEY，"
                            + "或上传对应字幕文件。");
        }

        try {
            Path audio = work.resolve("audio.mp3");
            run(List.of(tools.getFfmpeg(), "-y", "-i", video.toString(),
                    "-vn", "-ac", "1", "-ar", "16000", "-c:a", "libmp3lame",
                    "-b:a", "48k", audio.toString()),
                    work.resolve("audio.log"), 300);
            if (!Files.isRegularFile(audio)) {
                throw new IllegalStateException("ffmpeg 没有生成音频文件");
            }
            List<CaptionCue> cues = asrPort.transcribe(audio);
            if (cues.isEmpty()) {
                throw new IllegalStateException("ASR 没有返回字幕");
            }
            return cues;
        } catch (Exception e) {
            throw new IllegalStateException("视频转写失败: " + e.getMessage(), e);
        } finally {
            cleanup(work);
        }
    }

    private void run(List<String> command, Path log, int timeoutSeconds) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("外部命令超时: " + command.get(0));
        }
        if (process.exitValue() != 0) {
            String output = Files.isRegularFile(log) ? Files.readString(log) : "";
            throw new IllegalStateException("外部命令失败: " + command.get(0) + " "
                    + output.substring(Math.max(0, output.length() - 500)));
        }
    }

    private static void cleanup(Path dir) {
        if (dir == null) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // 临时文件清理失败不影响主流程
                }
            });
        } catch (Exception ignored) {
            // ignore
        }
    }
}
