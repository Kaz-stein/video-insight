package com.zhaozhenbo.video.adapter.caption;

import com.zhaozhenbo.video.domain.CaptionCue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaptionParserTest {

    private final CaptionParser parser = new CaptionParser();

    @Test
    @DisplayName("SRT：BOM + CRLF + 多行文本 + 样式标签")
    void parsesSrt() {
        String srt = "\uFEFF1\r\n"
                + "00:00:01,000 --> 00:00:03,500\r\n"
                + "<i>大家好</i>\r\n"
                + "欢迎来到本期视频\r\n"
                + "\r\n"
                + "2\r\n"
                + "00:00:04,000 --> 00:00:06,000\r\n"
                + "今天聊 Java\r\n";

        List<CaptionCue> cues = parser.parse(srt);

        assertThat(cues).hasSize(2);
        assertThat(cues.get(0).index()).isZero();
        assertThat(cues.get(0).startMs()).isEqualTo(1_000);
        assertThat(cues.get(0).endMs()).isEqualTo(3_500);
        assertThat(cues.get(0).text()).isEqualTo("大家好 欢迎来到本期视频");
        assertThat(cues.get(1).text()).isEqualTo("今天聊 Java");
    }

    @Test
    @DisplayName("VTT：省略小时、带 cue 设置、无序号行、NOTE 块")
    void parsesVtt() {
        String vtt = "WEBVTT\n"
                + "\n"
                + "00:01.000 --> 00:03.000 align:start position:0%\n"
                + "第一条字幕\n"
                + "\n"
                + "NOTE 这是注释，不应被当成字幕\n"
                + "\n"
                + "00:04.500 --> 00:06.250\n"
                + "第二条字幕\n";

        List<CaptionCue> cues = parser.parse(vtt);

        assertThat(cues).hasSize(2);
        assertThat(cues.get(0).startMs()).isEqualTo(1_000);
        assertThat(cues.get(0).endMs()).isEqualTo(3_000);
        assertThat(cues.get(1).startMs()).isEqualTo(4_500);
        assertThat(cues.get(1).text()).isEqualTo("第二条字幕");
    }

    @Test
    @DisplayName("乱序文件：按开始时间排序后重新编号")
    void sortsOutOfOrder() {
        String srt = "1\n00:00:10,000 --> 00:00:12,000\n后面\n\n"
                + "2\n00:00:01,000 --> 00:00:03,000\n前面\n";

        List<CaptionCue> cues = parser.parse(srt);

        assertThat(cues).extracting(CaptionCue::text).containsExactly("前面", "后面");
        assertThat(cues).extracting(CaptionCue::index).containsExactly(0, 1);
    }

    @Test
    @DisplayName("cue 之间没有空行也能正确切分")
    void parsesWithoutBlankLines() {
        String srt = "1\n00:00:01,000 --> 00:00:02,000\n一\n"
                + "2\n00:00:03,000 --> 00:00:04,000\n二\n";

        List<CaptionCue> cues = parser.parse(srt);

        assertThat(cues).hasSize(2);
        assertThat(cues.get(1).text()).isEqualTo("二");
    }

    @Test
    @DisplayName("空输入 / 没有时间轴的文件直接报错")
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("为空");

        assertThatThrownBy(() -> parser.parse("这不是字幕，只是一段普通文本"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未解析出任何字幕");
    }
}
