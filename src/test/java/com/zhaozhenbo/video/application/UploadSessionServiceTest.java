package com.zhaozhenbo.video.application;

import com.zhaozhenbo.video.adapter.persistence.VideoTaskEntity;
import com.zhaozhenbo.video.api.dto.CreateUploadRequest;
import com.zhaozhenbo.video.api.dto.UploadStatusResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "video.upload-dir=target/test-chunk-uploads",
        "video.mq.enabled=false"
})
@ActiveProfiles("test")
@Transactional
class UploadSessionServiceTest {

    @Autowired
    private UploadSessionService uploads;

    @Test
    @DisplayName("分片上传：断点查询、重复 PUT 幂等、合并后创建 VIDEO 任务")
    void chunkedUploadAndResume() throws Exception {
        int chunkSize = 1024 * 1024;
        byte[] first = new byte[chunkSize];
        for (int i = 0; i < first.length; i++) first[i] = (byte) (i % 251);
        byte[] last = "tail-data".getBytes(StandardCharsets.UTF_8);
        long totalSize = first.length + last.length;
        int totalChunks = 2;

        UploadStatusResponse created = uploads.create(
                new CreateUploadRequest("demo.mp4", totalSize, totalChunks, chunkSize));
        uploads.putChunk(created.uploadId(), 0, first, sha256(first));

        UploadStatusResponse afterFirst = uploads.status(created.uploadId());
        assertThat(afterFirst.uploadedChunks()).containsExactly(0);

        // 模拟断网重连：重复上传第 0 片，不应报错，也不应重复计数
        uploads.putChunk(created.uploadId(), 0, first, sha256(first));
        assertThat(uploads.status(created.uploadId()).uploadedChunks()).containsExactly(0);

        uploads.putChunk(created.uploadId(), 1, last, sha256(last));
        VideoTaskEntity task = uploads.complete(created.uploadId(), "分片测试");

        assertThat(task.getSourceType()).isEqualTo("VIDEO");
        assertThat(task.getStatus().name()).isEqualTo("PENDING");
        assertThat(Files.size(Path.of(task.getSourceRef()))).isEqualTo(totalSize);
        assertThat(uploads.status(created.uploadId()).status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("分片校验和不匹配直接拒绝")
    void rejectsBadChecksum() {
        UploadStatusResponse created = uploads.create(
                new CreateUploadRequest("demo.srt", 10, 1, 1024 * 1024));

        assertThatThrownBy(() -> uploads.putChunk(
                created.uploadId(), 0, "0123456789".getBytes(StandardCharsets.UTF_8),
                "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sha256");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
