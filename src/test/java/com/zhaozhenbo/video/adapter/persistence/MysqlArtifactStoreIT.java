package com.zhaozhenbo.video.adapter.persistence;

import com.zhaozhenbo.video.domain.ArtifactKind;
import com.zhaozhenbo.video.pipeline.agent.Plan;
import com.zhaozhenbo.video.pipeline.agent.PlanSection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可选的 MySQL 验证：确认 JSON 列在真实 MySQL 上也能读写。
 * 运行：$env:MYSQL_IT='true'; $env:DB_PASSWORD='...'; mvn -Dtest=MysqlArtifactStoreIT test
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "MYSQL_IT", matches = "true")
class MysqlArtifactStoreIT {

    @Autowired
    private JdbcArtifactStore store;

    @Test
    @DisplayName("MySQL JSON 列：artifact 写入再读回")
    void saveAndLoad() {
        String fingerprint = "a".repeat(64);
        Plan plan = new Plan(List.of(new PlanSection("MySQL 验证", List.of(0))));

        store.save(fingerprint, ArtifactKind.PLAN, plan);
        Plan loaded = store.load(fingerprint, ArtifactKind.PLAN, Plan.class).orElseThrow();

        assertThat(loaded).isEqualTo(plan);
    }
}
