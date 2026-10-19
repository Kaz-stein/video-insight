package com.zhaozhenbo.video.adapter.persistence;

import com.zhaozhenbo.video.domain.ArtifactKind;
import com.zhaozhenbo.video.pipeline.agent.Plan;
import com.zhaozhenbo.video.pipeline.agent.PlanSection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JdbcArtifactStoreTest {

    @Autowired
    private JdbcArtifactStore store;

    @Test
    @DisplayName("artifact 写入 JSON 列再读回，内容不丢")
    void saveAndLoad() {
        String fingerprint = "e".repeat(64);
        Plan plan = new Plan(List.of(
                new PlanSection("开场", List.of(0, 1)),
                new PlanSection("核心", List.of(2))));

        store.save(fingerprint, ArtifactKind.PLAN, plan);
        Plan loaded = store.load(fingerprint, ArtifactKind.PLAN, Plan.class).orElseThrow();

        assertThat(loaded).isEqualTo(plan);
    }
}
