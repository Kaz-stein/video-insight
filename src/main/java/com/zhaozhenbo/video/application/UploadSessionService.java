package com.zhaozhenbo.video.application;

import com.zhaozhenbo.video.adapter.persistence.VideoTaskEntity;
import com.zhaozhenbo.video.adapter.persistence.VideoTaskRepository;
import com.zhaozhenbo.video.api.dto.ChunkResponse;
import com.zhaozhenbo.video.api.dto.CreateUploadRequest;
import com.zhaozhenbo.video.api.dto.UploadStatusResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 分片上传会话。
 *
 * 设计目标只有一个：1GB 文件传到 80% 断网，重连后只补缺失分片。
 * 分片校验和 + 唯一键保证重复 PUT 不会写坏；complete 再算整体 sha256。
 */
@Service
public class UploadSessionService {

    private static final long MAX_TOTAL_SIZE = 10L * 1024 * 1024 * 1024;
    private static final long MIN_CHUNK_SIZE = 1024 * 1024;
    private static final long MAX_CHUNK_SIZE = 32L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final TaskSubmissionService submission;
    private final VideoTaskRepository tasks;
    private final Path uploadDir;
    private final long userId;

    public UploadSessionService(JdbcTemplate jdbc,
                                TaskSubmissionService submission,
                                VideoTaskRepository tasks,
                                @Value("${video.upload-dir:data/uploads}") String uploadDir,
                                @Value("${video.user-id:1}") long userId) {
        this.jdbc = jdbc;
        this.submission = submission;
        this.tasks = tasks;
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        this.userId = userId;
    }

    public UploadStatusResponse create(CreateUploadRequest request) {
        if (request == null || request.fileName() == null || request.fileName().isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (request.totalSize() <= 0 || request.totalSize() > MAX_TOTAL_SIZE) {
            throw new IllegalArgumentException("文件大小必须在 1B ~ 10GB 之间");
        }
        if (request.chunkSize() < MIN_CHUNK_SIZE || request.chunkSize() > MAX_CHUNK_SIZE) {
            throw new IllegalArgumentException("分片大小必须在 1MB ~ 32MB 之间");
        }
        long expectedChunks = (request.totalSize() + request.chunkSize() - 1) / request.chunkSize();
        if (request.totalChunks() != expectedChunks) {
            throw new IllegalArgumentException("totalChunks 与文件大小不匹配");
        }

        String extension = extensionOf(request.fileName());
        if (!isVideo(extension) && !isSubtitle(extension)) {
            throw new IllegalArgumentException("只支持视频（mp4/mkv/mov/webm）或字幕（srt/vtt）");
        }

        String id = UUID.randomUUID().toString();
        jdbc.update("""
                insert into upload_session
                    (id, user_id, file_name, extension, total_chunks, chunk_size, total_size, status)
                values (?, ?, ?, ?, ?, ?, ?, 'UPLOADING')
                """, id, userId, request.fileName(), extension,
                request.totalChunks(), request.chunkSize(), request.totalSize());
        return status(id);
    }

    public ChunkResponse putChunk(String uploadId, int index, byte[] data, String checksum) {
        Session session = load(uploadId);
        if (!"UPLOADING".equals(session.status())) {
            throw new IllegalStateException("上传会话当前不可写入: " + session.status());
        }
        if (index < 0 || index >= session.totalChunks()) {
            throw new IllegalArgumentException("分片下标越界: " + index);
        }
        long offset = (long) index * session.chunkSize();
        int expectedSize = (int) Math.min(session.chunkSize(), session.totalSize() - offset);
        if (data == null || data.length != expectedSize) {
            throw new IllegalArgumentException(
                    "分片大小不对: 期望 " + expectedSize + "，实际 " + (data == null ? 0 : data.length));
        }
        String actual = sha256(data);
        if (checksum == null || !actual.equalsIgnoreCase(checksum)) {
            throw new IllegalArgumentException("分片 sha256 校验失败");
        }

        Path dir = sessionDir(uploadId);
        Path part = dir.resolve(index + ".part");
        try {
            Files.createDirectories(dir);
            Path temp = dir.resolve(index + ".part.tmp");
            Files.write(temp, data);
            Files.move(temp, part, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

            int updated = jdbc.update("""
                    update upload_chunk set size = ?, checksum = ?
                     where session_id = ? and chunk_index = ?
                    """, data.length, actual, uploadId, index);
            if (updated == 0) {
                try {
                    jdbc.update("""
                            insert into upload_chunk (session_id, chunk_index, size, checksum)
                            values (?, ?, ?, ?)
                            """, uploadId, index, data.length, actual);
                } catch (DuplicateKeyException race) {
                    jdbc.update("""
                            update upload_chunk set size = ?, checksum = ?
                             where session_id = ? and chunk_index = ?
                            """, data.length, actual, uploadId, index);
                }
            }
            return new ChunkResponse(index, uploadedCount(uploadId), session.totalChunks());
        } catch (Exception e) {
            throw new IllegalStateException("分片写入失败: " + e.getMessage(), e);
        }
    }

    public UploadStatusResponse status(String uploadId) {
        Session session = load(uploadId);
        List<Integer> uploaded = jdbc.queryForList("""
                select chunk_index from upload_chunk
                 where session_id = ? order by chunk_index
                """, Integer.class, uploadId);
        return new UploadStatusResponse(uploadId, session.status(), session.totalChunks(),
                session.chunkSize(), session.totalSize(), uploaded, session.taskId());
    }

    public VideoTaskEntity complete(String uploadId, String title) {
        return complete(uploadId, null, title);
    }

    /**
     * 合并分片并创建任务。
     *
     * @param expectedSha256 调用方给出的整文件 sha256；为空则跳过整文件校验
     */
    public VideoTaskEntity complete(String uploadId, String expectedSha256, String title) {
        Session session = load(uploadId);
        if ("COMPLETED".equals(session.status()) && session.taskId() != null) {
            return tasks.findById(session.taskId())
                    .orElseThrow(() -> new IllegalStateException("已上传任务不存在"));
        }
        if (!"UPLOADING".equals(session.status())) {
            throw new IllegalStateException("上传会话当前不可合并: " + session.status());
        }
        if (uploadedCount(uploadId) != session.totalChunks()) {
            throw new IllegalStateException("分片还没有传完");
        }

        jdbc.update("update upload_session set status = 'MERGING' where id = ?", uploadId);
        Path merged = uploadDir.resolve(uploadId + session.extension()).normalize();
        if (!merged.startsWith(uploadDir)) {
            throw new IllegalStateException("合并路径越界");
        }
        try {
            Files.createDirectories(uploadDir);
            String actualSha256 = mergeChunks(uploadId, session.totalChunks(), merged);
            if (Files.size(merged) != session.totalSize()) {
                throw new IllegalStateException("合并后文件大小不一致");
            }
            // 整文件校验：分片校验已经保证了每片内容，这里再兜一次"顺序错/拼接错"的底
            if (expectedSha256 != null && !expectedSha256.isBlank()
                    && !actualSha256.equalsIgnoreCase(expectedSha256.trim())) {
                Files.deleteIfExists(merged);
                jdbc.update("update upload_session set status = 'FAILED' where id = ?", uploadId);
                throw new IntegrityException("整文件 sha256 不匹配，已丢弃合并结果");
            }

            String sourceType = isVideo(session.extension()) ? "VIDEO" : "SUBTITLE";
            String baseName = session.fileName().contains(".")
                    ? session.fileName().substring(0, session.fileName().lastIndexOf('.'))
                    : session.fileName();
            VideoTaskEntity task = submission.submitStoredFile(merged, baseName, sourceType, title);
            jdbc.update("""
                    update upload_session set status = 'COMPLETED', task_id = ? where id = ?
                    """, task.getId(), uploadId);
            cleanup(sessionDir(uploadId));
            return task;
        } catch (IntegrityException e) {
            throw e;
        } catch (Exception e) {
            jdbc.update("update upload_session set status = 'FAILED' where id = ?", uploadId);
            throw new IllegalStateException("合并上传文件失败: " + e.getMessage(), e);
        }
    }

    /** 顺序拼接所有分片，边写边算整文件 sha256，避免再读一遍磁盘。 */
    private String mergeChunks(String uploadId, int totalChunks, Path merged) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1 << 16];
        try (OutputStream out = Files.newOutputStream(merged)) {
            for (int i = 0; i < totalChunks; i++) {
                Path part = sessionDir(uploadId).resolve(i + ".part");
                try (InputStream in = Files.newInputStream(part)) {
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        digest.update(buffer, 0, read);
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Session load(String uploadId) {
        List<Session> sessions = jdbc.query("""
                select id, user_id, file_name, extension, total_chunks, chunk_size,
                       total_size, status, task_id
                  from upload_session where id = ?
                """, (rs, rowNum) -> new Session(
                rs.getString("id"),
                rs.getLong("user_id"),
                rs.getString("file_name"),
                rs.getString("extension"),
                rs.getInt("total_chunks"),
                rs.getLong("chunk_size"),
                rs.getLong("total_size"),
                rs.getString("status"),
                rs.getObject("task_id") == null ? null : rs.getLong("task_id")), uploadId);
        if (sessions.isEmpty()) {
            throw new IllegalArgumentException("上传会话不存在: " + uploadId);
        }
        Session session = sessions.getFirst();
        if (session.userId() != userId) {
            throw new IllegalArgumentException("上传会话不存在: " + uploadId);
        }
        return session;
    }

    private int uploadedCount(String uploadId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from upload_chunk where session_id = ?", Integer.class, uploadId);
        return count == null ? 0 : count;
    }

    private Path sessionDir(String uploadId) {
        Path dir = uploadDir.resolve(".sessions").resolve(uploadId).normalize();
        if (!dir.startsWith(uploadDir)) {
            throw new IllegalStateException("上传路径越界");
        }
        return dir;
    }

    private static boolean isVideo(String extension) {
        return List.of(".mp4", ".mkv", ".mov", ".webm", ".m4v", ".avi", ".flv",
                ".ts", ".m2ts", ".wmv", ".mpg", ".mpeg").contains(extension);
    }

    private static boolean isSubtitle(String extension) {
        return List.of(".srt", ".vtt").contains(extension);
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void cleanup(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
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

    private record Session(
            String id,
            long userId,
            String fileName,
            String extension,
            int totalChunks,
            long chunkSize,
            long totalSize,
            String status,
            Long taskId
    ) {
    }

    /**
     * 合并出来的文件和调用方声明的哈希对不上。
     *
     * 继承 IllegalArgumentException，是为了让 ApiExceptionHandler 把它映射成 400，
     * 而不是当成服务端故障的 500。
     */
    private static final class IntegrityException extends IllegalArgumentException {
        IntegrityException(String message) {
            super(message);
        }
    }
}
