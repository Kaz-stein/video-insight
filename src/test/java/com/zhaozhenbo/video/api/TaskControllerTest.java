package com.zhaozhenbo.video.api;

import com.zhaozhenbo.video.adapter.mq.InMemoryTaskQueueAdapter;
import com.zhaozhenbo.video.application.TaskPipelineService;
import com.zhaozhenbo.video.domain.TaskMessage;
import com.zhaozhenbo.video.domain.TaskStatus;
import com.zhaozhenbo.video.port.TaskQueuePort;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T6 端到端：上传 SRT -> 落任务 -> 手动投递一条消息 -> 六阶段执行
 * -> note/note_point 落库 -> HTTP 查询与 Markdown 下载。
 */
@SpringBootTest(properties = {
        "video.pipeline.real-handlers=true",
        "video.llm.provider=fake",
        "video.mq.enabled=false",
        "video.upload-dir=target/test-uploads",
        "video.mq.compensation-interval-ms=3600000"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class TaskControllerTest {

    private static final String SRT = """
            1
            00:00:00,000 --> 00:00:08,000
            Java 线程池的核心参数包括核心线程数、最大线程数和队列长度。

            2
            00:00:08,000 --> 00:00:16,000
            当任务数量超过核心线程数时，任务会先进入阻塞队列等待。

            3
            00:00:16,000 --> 00:00:24,000
            只有队列满了，线程池才会创建非核心线程。
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private TaskQueuePort queue;

    @Autowired
    private TaskPipelineService pipeline;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void clearQueue() {
        ((InMemoryTaskQueueAdapter) queue).clear();
    }

    @Test
    @DisplayName("上传 -> 执行 -> 查询 -> 下载 Markdown")
    void submitRunAndFetchNote() throws Exception {
        long taskId = submit("线程池讲解");

        mockMvc.perform(get("/api/tasks/{id}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 内存队列没有后台消费者，测试里手动把消息交给 pipeline
        TaskMessage message = ((InMemoryTaskQueueAdapter) queue).poll();
        assertThat(message).isNotNull();
        pipeline.handle(message);

        mockMvc.perform(get("/api/tasks/{id}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.stage").value("COMPOSING"));

        mockMvc.perform(get("/api/tasks/{id}/note", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").isNotEmpty())
                .andExpect(jsonPath("$.points[0].status").value("GROUNDED"))
                .andExpect(jsonPath("$.points[0].overlap").value(1.0));

        mockMvc.perform(get("/api/tasks/{id}/note.md", taskId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.parseMediaType("text/markdown;charset=UTF-8")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("# 线程池讲解")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 时间轴")));

        Integer noteCount = jdbc.queryForObject("select count(*) from note", Integer.class);
        Integer pointCount = jdbc.queryForObject("select count(*) from note_point", Integer.class);
        assertThat(noteCount).isEqualTo(1);
        assertThat(pointCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("同一文件重复提交 -> 同一个 taskId，不重复建任务")
    void submitIsIdempotent() throws Exception {
        long first = submit("第一次");
        long second = submit("第二次");

        assertThat(second).isEqualTo(first);
        assertThat(((InMemoryTaskQueueAdapter) queue).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("笔记未生成时查询 -> 409")
    void noteNotReady() throws Exception {
        long taskId = submit("未完成");

        mockMvc.perform(get("/api/tasks/{id}/note", taskId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("尚未生成")));
    }

    @Test
    @DisplayName("FAILED 任务可以 retry，重新投递 PENDING")
    void retryFailedTask() throws Exception {
        long taskId = submit("可重试");
        TaskMessage message = ((InMemoryTaskQueueAdapter) queue).poll();
        assertThat(message).isNotNull();

        // 模拟执行失败
        jdbc.update("update video_task set status='FAILED', stage='SEGMENTING', error='模拟失败' where id=?", taskId);
        entityManager.clear(); // JdbcTemplate 绕过了 JPA 一级缓存
        ((InMemoryTaskQueueAdapter) queue).clear();

        mockMvc.perform(post("/api/tasks/{id}/retry", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.stage").value("SEGMENTING"));

        assertThat(((InMemoryTaskQueueAdapter) queue).size()).isEqualTo(1);
    }

    private long submit(String title) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "demo.srt", "text/plain", SRT.getBytes(StandardCharsets.UTF_8));
        MvcResult result = mockMvc.perform(multipart("/api/tasks")
                        .file(file)
                        .param("title", title))
                .andExpect(status().isAccepted())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}
