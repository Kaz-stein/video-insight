package com.zhaozhenbo.video.adapter.persistence;

import com.zhaozhenbo.video.domain.ArtifactKind;
import com.zhaozhenbo.video.port.ArtifactStorePort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * artifact 用 JdbcTemplate 而不是 JPA：
 * JSON 列在不同数据库上的类型映射差异很大，直接读写字符串最可控。
 */
@Repository
public class JdbcArtifactStore implements ArtifactStorePort {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcArtifactStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public <T> Optional<T> load(String fingerprint, ArtifactKind kind, Class<T> type) {
        List<String> payloads = jdbc.query(
                "select payload from video_artifact where fingerprint = ? and stage = ?",
                (rs, rowNum) -> rs.getString(1),
                fingerprint,
                kind.stageKey());
        if (payloads.isEmpty()) {
            return Optional.empty();
        }
        try {
            String payload = payloads.getFirst();
            // H2 的 JSON 列会把 setString 当成 JSON 字符串值，读出来多一层引号；
            // MySQL 的 JSON 列读出来是原对象。这里兼容两种行为。
            if (payload.startsWith("\"") && payload.endsWith("\"")) {
                payload = mapper.readValue(payload, String.class);
            }
            return Optional.of(mapper.readValue(payload, type));
        } catch (Exception e) {
            throw new IllegalStateException("artifact 反序列化失败: " + kind.stageKey(), e);
        }
    }

    @Override
    public void save(String fingerprint, ArtifactKind kind, Object payload) {
        try {
            String json = mapper.writeValueAsString(payload);
            String checksum = sha256(json);
            int updated = jdbc.update(
                    "update video_artifact set payload = CAST(? AS JSON), checksum = ? "
                            + "where fingerprint = ? and stage = ?",
                    json, checksum, fingerprint, kind.stageKey());
            if (updated == 0) {
                try {
                    jdbc.update(
                            "insert into video_artifact (fingerprint, stage, payload, checksum) "
                                    + "values (?, ?, CAST(? AS JSON), ?)",
                            fingerprint, kind.stageKey(), json, checksum);
                } catch (DuplicateKeyException race) {
                    // 两个任务同时算同一阶段：后到的更新即可，产物内容相同
                    jdbc.update(
                            "update video_artifact set payload = CAST(? AS JSON), checksum = ? "
                                    + "where fingerprint = ? and stage = ?",
                            json, checksum, fingerprint, kind.stageKey());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("artifact 写入失败: " + kind.stageKey(), e);
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
