package com.zhaozhenbo.video.adapter.persistence;

import com.zhaozhenbo.video.domain.Stage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * stage_log：每个阶段每次尝试一行。
 *
 * 这张表是“可观测性”的底座：
 *   - 哪个阶段慢（duration_ms）
 *   - 哪个阶段老是失败（status + error）
 *   - 重试了几次（attempt，配合唯一键）
 */
@Entity
@Table(name = "stage_log")
public class StageLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private long taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 24)
    private Stage stage;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "started_at", nullable = false, precision = 3)
    private LocalDateTime startedAt;

    @Column(name = "ended_at", precision = 3)
    private LocalDateTime endedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "tokens")
    private Integer tokens;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getTaskId() {
        return taskId;
    }

    public void setTaskId(long taskId) {
        this.taskId = taskId;
    }

    public Stage getStage() {
        return stage;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(LocalDateTime endedAt) {
        this.endedAt = endedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Integer getTokens() {
        return tokens;
    }

    public void setTokens(Integer tokens) {
        this.tokens = tokens;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
