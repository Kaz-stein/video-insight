package com.zhaozhenbo.video.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evaltest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "video.mq.enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class EvalServiceTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EvalService evalService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("2 GROUNDED + 1 INFERRED + 1 REJECTED：可回溯率 0.5，无依据率 0.25")
    void computesMetrics() throws Exception {
        LocalDateTime created = LocalDateTime.now().minusSeconds(2);
        LocalDateTime updated = LocalDateTime.now();
        jdbc.update("""
                insert into video_task
                    (id, user_id, source_type, source_ref, fingerprint, params_hash, title,
                     status, stage, created_at, updated_at)
                values (100, 1, 'FILE', 'x.srt', ?, ?, 'eval', 'DONE', 'COMPOSING', ?, ?)
                """, "a".repeat(64), "b".repeat(64),
                Timestamp.valueOf(created), Timestamp.valueOf(updated));
        jdbc.update("""
                insert into note
                    (id, user_id, video_fingerprint, task_id, summary, markdown, outline, model, prompt_version)
                values (100, 1, ?, 100, 'summary', '# note', CAST('[]' AS JSON), 'fake', 'v1')
                """, "a".repeat(64));
        insertPoint(0, "GROUNDED");
        insertPoint(1, "GROUNDED");
        insertPoint(2, "INFERRED");
        insertPoint(3, "REJECTED");

        var result = evalService.evaluate();

        org.assertj.core.api.Assertions.assertThat(result.citationRecall()).isEqualTo(0.5);
        org.assertj.core.api.Assertions.assertThat(result.unsupportedRate()).isEqualTo(0.25);
        org.assertj.core.api.Assertions.assertThat(result.avgDurationMs()).isEqualTo(2000);

        mockMvc.perform(get("/api/eval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citationRecall").value(0.5))
                .andExpect(jsonPath("$.unsupportedRate").value(0.25))
                .andExpect(jsonPath("$.avgDurationMs").value(2000));
    }

    private void insertPoint(int seq, String status) {
        jdbc.update("""
                insert into note_point
                    (note_id, seq, text, quote, section, start_ms, end_ms, overlap, status)
                values (100, ?, ?, ?, 's', 0, 1000, 1.0, ?)
                """, seq, "point-" + seq, "quote-" + seq, status);
    }
}
