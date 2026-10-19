package com.zhaozhenbo.video.config;

import com.zhaozhenbo.video.adapter.llm.LlmProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 始终注册配置，保证无真实流水线时提交服务也能读到 model 名。 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {
}
