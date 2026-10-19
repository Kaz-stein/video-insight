package com.zhaozhenbo.video.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoUrlExtractorTest {

    @Test
    @DisplayName("从整段 B 站分享文案提取 URL，并去掉分享追踪参数")
    void extractsFromShareText() {
        String share = "【我的 AI 编程全流程】 https://www.bilibili.com/video/BV154426xEha/"
                + "?share_source=copy_web&vd_source=e477dbbdc0bce7e3601a95c7d0c8e11b 看看这个";

        assertThat(VideoUrlExtractor.extract(share))
                .isEqualTo("https://www.bilibili.com/video/BV154426xEha");
        assertThat(VideoUrlExtractor.extractTitle(share)).isEqualTo("我的 AI 编程全流程");
    }

    @Test
    @DisplayName("b23 短链、纯 BV 号、分 P 都能识别")
    void extractsShortAndBv() {
        assertThat(VideoUrlExtractor.extract("【标题】https://b23.tv/AbC123?share_source=x"))
                .isEqualTo("https://b23.tv/AbC123");
        assertThat(VideoUrlExtractor.extract("BV154426xEha"))
                .isEqualTo("https://www.bilibili.com/video/BV154426xEha");
        assertThat(VideoUrlExtractor.extract("https://www.bilibili.com/video/BV154426xEha?p=2&vd_source=x"))
                .isEqualTo("https://www.bilibili.com/video/BV154426xEha?p=2");
    }

    @Test
    @DisplayName("没有视频链接时明确报错")
    void rejectsPlainText() {
        assertThatThrownBy(() -> VideoUrlExtractor.extract("今天天气不错，没有链接"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有识别到视频链接");
    }
}
