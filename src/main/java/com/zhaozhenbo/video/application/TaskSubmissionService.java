package com.zhaozhenbo.video.application;

import com.zhaozhenbo.video.adapter.llm.LlmProperties;
import com.zhaozhenbo.video.adapter.persistence.VideoTaskEntity;
import com.zhaozhenbo.video.adapter.persistence.VideoTaskRepository;
import com.zhaozhenbo.video.adapter.persistence.StageLogRepository;
import com.zhaozhenbo.video.api.TaskNotFoundException;
import com.zhaozhenbo.video.api.TaskNotRetryableException;
import com.zhaozhenbo.video.domain.ArtifactKind;
import com.zhaozhenbo.video.domain.TaskMessage;
import com.zhaozhenbo.video.domain.TaskStatus;
import com.zhaozhenbo.video.pipeline.agent.Prompts;
import com.zhaozhenbo.video.port.TaskQueuePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 提交/重试任务。
 *
 * 关键顺序：先落 MySQL，再发 MQ。发送失败时任务仍是 PENDING，
 * 由补偿任务每 30s 重投，不会出现“消息发了但库里没有”的幽灵任务。
 */
@Service
public class TaskSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(TaskSubmissionService.class);
    private static final Set<String> VIDEO_EXTENSIONS =
            Set.of(".mp4", ".mkv", ".mov", ".webm", ".m4v", ".avi", ".flv");
    private static final Set<String> SUBTITLE_EXTENSIONS = Set.of(".srt", ".vtt");

    private final VideoTaskRepository tasks;
    private final TaskQueuePort queue;
    private final StageLogRepository stageLogs;
    private final LlmProperties llmProperties;
    private final Path uploadDir;
    private final long userId;

    public TaskSubmissionService(VideoTaskRepository tasks,
                                 TaskQueuePort queue,
                                 StageLogRepository stageLogs,
                                 LlmProperties llmProperties,
                                 @Value("${video.upload-dir:data/uploads}") String uploadDir,
                                 @Value("${video.user-id:1}") long userId) {
        this.tasks = tasks;
        this.queue = queue;
        this.stageLogs = stageLogs;
        this.llmProperties = llmProperties;
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        this.userId = userId;
    }

    public VideoTaskEntity submit(MultipartFile file, String title) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        String original = file.getOriginalFilename() == null ? "upload.srt" : file.getOriginalFilename();
        String extension = extensionOf(original);
        boolean video = VIDEO_EXTENSIONS.contains(extension);
        boolean subtitle = SUBTITLE_EXTENSIONS.contains(extension);
        if (!video && !subtitle) {
            throw new IllegalArgumentException(
                    "支持视频（mp4/mkv/mov/webm）或字幕（srt/vtt）文件");
        }

        Path target = saveUpload(file, extension);
        return submitStoredFile(target, stripExtension(original),
                video ? "VIDEO" : "SUBTITLE", title);
    }

    /**
     * 已经落在磁盘上的完整文件（分片合并完成）创建任务。
     * 指纹按文件内容计算，和单请求上传共用同一套幂等键。
     */
    public VideoTaskEntity submitStoredFile(Path file, String originalName, String sourceType, String title) {
        String fingerprint = sha256(file);
        String paramsHash = paramsHash();
        var existing = tasks.findByUserIdAndFingerprintAndParamsHash(userId, fingerprint, paramsHash);
        if (existing.isPresent()) {
            return existing.get();
        }

        VideoTaskEntity task = new VideoTaskEntity();
        task.setUserId(userId);
        task.setSourceType(sourceType);
        task.setSourceRef(file.toString());
        task.setFingerprint(fingerprint);
        task.setParamsHash(paramsHash);
        task.setTitle(title == null || title.isBlank() ? originalName : title.strip());
        task.setStatus(TaskStatus.PENDING);
        task = tasks.save(task);
        enqueue(task.getId());
        return task;
    }

    /**
     * 视频链接任务。链接只存 URL，不下载整段视频；
     * FETCHING 阶段用 yt-dlp 只取字幕。
     */
    public VideoTaskEntity submitLink(String url, String title) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("视频链接不能为空");
        }
        String normalized = url.strip();
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (Exception e) {
            throw new IllegalArgumentException("视频链接格式不正确");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("只支持 http(s) 视频链接");
        }

        String fingerprint = sha256(normalized.getBytes(StandardCharsets.UTF_8));
        String paramsHash = paramsHash();
        var existing = tasks.findByUserIdAndFingerprintAndParamsHash(userId, fingerprint, paramsHash);
        if (existing.isPresent()) {
            return existing.get();
        }

        VideoTaskEntity task = new VideoTaskEntity();
        task.setUserId(userId);
        task.setSourceType(host.endsWith("bilibili.com") || host.equals("b23.tv") ? "BILIBILI" : "LINK");
        task.setSourceRef(normalized);
        task.setFingerprint(fingerprint);
        task.setParamsHash(paramsHash);
        task.setTitle(title == null || title.isBlank() ? linkTitle(uri) : title.strip());
        task.setStatus(TaskStatus.PENDING);
        task = tasks.save(task);
        enqueue(task.getId());
        return task;
    }

    public VideoTaskEntity retry(long taskId) {
        VideoTaskEntity task = tasks.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        if (task.getStatus() != TaskStatus.FAILED) {
            throw new TaskNotRetryableException("只有 FAILED 任务可以重试，当前是 " + task.getStatus());
        }

        int updated = tasks.resetForRetry(taskId, TaskStatus.PENDING, TaskStatus.FAILED, LocalDateTime.now());
        if (updated == 0) {
            throw new TaskNotRetryableException("任务状态已变化，请刷新后再试");
        }
        try {
            // 手动重试不能从 1 开始：stage_log 的唯一键是 (task, stage, attempt)
            int nextAttempt = Math.max(1, stageLogs.findMaxAttempt(taskId) + 1);
            queue.enqueue(new TaskMessage(taskId, nextAttempt));
        } catch (Exception e) {
            log.error("任务 {} 重试投递失败，等待补偿", taskId, e);
        }
        return tasks.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    private void enqueue(long taskId) {
        try {
            queue.enqueue(new TaskMessage(taskId, 1));
        } catch (Exception e) {
            // 保持 PENDING，补偿任务会补发；接口仍然返回 202
            log.error("任务 {} 首次投递失败，等待补偿", taskId, e);
        }
    }

    private Path saveUpload(MultipartFile file, String extension) {
        try {
            Files.createDirectories(uploadDir);
            Path target = uploadDir.resolve(UUID.randomUUID() + extension).normalize();
            if (!target.startsWith(uploadDir)) {
                throw new IllegalStateException("上传路径越界");
            }
            file.transferTo(target);
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("保存上传文件失败", e);
        }
    }

    private static String linkTitle(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        return last.isBlank() ? "视频链接" : last;
    }

    /**
     * 参数指纹必须覆盖所有会改变输出的参数；改 prompt / 切段算法要 bump 版本。
     */
    private String paramsHash() {
        String canonical = "pipeline=" + ArtifactKind.PIPELINE_VERSION
                + "|planner=" + Prompts.PLANNER_VERSION
                + "|extractor=" + Prompts.EXTRACTOR_VERSION
                + "|critic=" + Prompts.CRITIC_VERSION
                + "|composer=" + Prompts.COMPOSER_VERSION
                + "|model=" + llmProperties.getModel()
                + "|temperature=0.3|style=default|language=zh|points=6";
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(Path path) {
        try (var input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("计算文件指纹失败", e);
        }
    }
}
