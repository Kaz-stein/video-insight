package com.zhaozhenbo.video.application;

import com.zhaozhenbo.video.adapter.llm.LlmProperties;
import com.zhaozhenbo.video.adapter.persistence.VideoTaskEntity;
import com.zhaozhenbo.video.adapter.persistence.VideoTaskRepository;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
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

    private final VideoTaskRepository tasks;
    private final TaskQueuePort queue;
    private final LlmProperties llmProperties;
    private final Path uploadDir;
    private final long userId;

    public TaskSubmissionService(VideoTaskRepository tasks,
                                 TaskQueuePort queue,
                                 LlmProperties llmProperties,
                                 @Value("${video.upload-dir:data/uploads}") String uploadDir,
                                 @Value("${video.user-id:1}") long userId) {
        this.tasks = tasks;
        this.queue = queue;
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
        if (!extension.equals(".srt") && !extension.equals(".vtt")) {
            throw new IllegalArgumentException("v1 只支持 .srt / .vtt 字幕文件");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败", e);
        }
        String fingerprint = sha256(bytes);
        String paramsHash = paramsHash();

        // 幂等：同用户 + 同内容 + 同参数，直接返回已有任务
        var existing = tasks.findByUserIdAndFingerprintAndParamsHash(userId, fingerprint, paramsHash);
        if (existing.isPresent()) {
            return existing.get();
        }

        Path target = saveUpload(bytes, extension);
        VideoTaskEntity task = new VideoTaskEntity();
        task.setUserId(userId);
        task.setSourceType("FILE");
        task.setSourceRef(target.toString());
        task.setFingerprint(fingerprint);
        task.setParamsHash(paramsHash);
        task.setTitle(title == null || title.isBlank() ? stripExtension(original) : title.strip());
        task.setStatus(TaskStatus.PENDING);
        task = tasks.save(task);

        try {
            queue.enqueue(new TaskMessage(task.getId(), 1));
        } catch (Exception e) {
            // 保持 PENDING，补偿任务会补发；接口仍然返回 202
            log.error("任务 {} 首次投递失败，等待补偿", task.getId(), e);
        }
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
            queue.enqueue(new TaskMessage(taskId, 1));
        } catch (Exception e) {
            log.error("任务 {} 重试投递失败，等待补偿", taskId, e);
        }
        return tasks.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    private Path saveUpload(byte[] bytes, String extension) {
        try {
            Files.createDirectories(uploadDir);
            Path target = uploadDir.resolve(UUID.randomUUID() + extension).normalize();
            if (!target.startsWith(uploadDir)) {
                throw new IllegalStateException("上传路径越界");
            }
            Files.write(target, bytes);
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("保存上传文件失败", e);
        }
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
}
