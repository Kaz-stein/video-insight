package com.zhaozhenbo.video.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 云 ASR：OpenAI 兼容的 /v1/audio/transcriptions 接口。 */
@ConfigurationProperties(prefix = "video.asr")
public class AsrProperties {

    private boolean enabled = false;
    /** openai / siliconflow */
    private String provider = "openai";
    private String baseUrl = "https://api.openai.com";
    private String apiKey = "";
    private String model = "whisper-1";
    private String language = "zh";
    private int timeoutSeconds = 300;
    private int maxAudioMb = 100;
    /** SiliconFlow 没有句级时间戳，按这个长度切片；证据精度约 ±chunk 秒 */
    private int chunkSeconds = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxAudioMb() {
        return maxAudioMb;
    }

    public void setMaxAudioMb(int maxAudioMb) {
        this.maxAudioMb = maxAudioMb;
    }

    public int getChunkSeconds() {
        return chunkSeconds;
    }

    public void setChunkSeconds(int chunkSeconds) {
        this.chunkSeconds = chunkSeconds;
    }
}
