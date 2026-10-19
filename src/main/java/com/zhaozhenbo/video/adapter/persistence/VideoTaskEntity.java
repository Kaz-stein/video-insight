package com.zhaozhenbo.video.adapter.persistence;

import com.zhaozhenbo.video.domain.Stage;
import com.zhaozhenbo.video.domain.TaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * video_task 的持久化模型。
 *
 * 只做“存取”，不把状态迁移规则写进实体；规则在 domain.TaskState 里，
 * 这样单测不需要数据库，实体也不会变成“上帝对象”。
 */
@Entity
@Table(name = "video_task")
public class VideoTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "source_type", nullable = false, length = 16)
    private String sourceType;

    @Column(name = "source_ref", nullable = false, length = 1024)
    private String sourceRef;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "params_hash", nullable = false, length = 64)
    private String paramsHash;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", length = 24)
    private Stage stage;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false, precision = 3)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, precision = 3)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getParamsHash() {
        return paramsHash;
    }

    public void setParamsHash(String paramsHash) {
        this.paramsHash = paramsHash;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public Stage getStage() {
        return stage;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
