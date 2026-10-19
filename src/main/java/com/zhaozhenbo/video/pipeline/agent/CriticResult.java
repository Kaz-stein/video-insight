package com.zhaozhenbo.video.pipeline.agent;

import java.util.Set;

/**
 * Critic 的结论。available=false 表示 Critic 不可用，按降级矩阵全部标 INFERRED。
 */
public record CriticResult(boolean available, Set<Integer> consistentIndexes) {

    public CriticResult {
        consistentIndexes = consistentIndexes == null ? Set.of() : Set.copyOf(consistentIndexes);
    }

    public static CriticResult unavailable() {
        return new CriticResult(false, Set.of());
    }

    public boolean isConsistent(int pointIndex) {
        return !available || consistentIndexes.contains(pointIndex);
    }
}
