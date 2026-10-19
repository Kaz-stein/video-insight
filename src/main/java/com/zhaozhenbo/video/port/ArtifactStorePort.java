package com.zhaozhenbo.video.port;

import com.zhaozhenbo.video.domain.ArtifactKind;

import java.util.Optional;

/**
 * 阶段产物存取。以 (fingerprint, kind) 为键，天然跨任务复用。
 * payload 里禁止放文件名、user_id 等身份信息。
 */
public interface ArtifactStorePort {

    <T> Optional<T> load(String fingerprint, ArtifactKind kind, Class<T> type);

    void save(String fingerprint, ArtifactKind kind, Object payload);
}
