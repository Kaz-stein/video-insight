package com.zhaozhenbo.video.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "video.rate-limit.mode=memory",
        "video.rate-limit.capacity=2",
        "video.rate-limit.refill-per-second=0.0001",
        "video.upload-dir=target/test-rate-limit-uploads"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RateLimitFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("令牌桶容量 2：第 3 次提交返回 429 和 Retry-After")
    void thirdRequestIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "rate.srt", "text/plain",
                "1\n00:00:00,000 --> 00:00:01,000\n限流测试。\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/tasks").file(file).header("X-User-Id", "rate-test"))
                .andExpect(status().isAccepted());
        mockMvc.perform(multipart("/api/tasks").file(file).header("X-User-Id", "rate-test"))
                .andExpect(status().isAccepted());
        mockMvc.perform(multipart("/api/tasks").file(file).header("X-User-Id", "rate-test"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("请求过于频繁")));
    }
}
