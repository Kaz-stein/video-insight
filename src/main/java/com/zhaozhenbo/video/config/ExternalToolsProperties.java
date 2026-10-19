package com.zhaozhenbo.video.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** ffmpeg / yt-dlp 的可执行文件位置。默认走 PATH，生产环境用环境变量覆盖。 */
@ConfigurationProperties(prefix = "video.tools")
public class ExternalToolsProperties {

    private String ffmpeg = "ffmpeg";
    private String ffprobe = "ffprobe";
    private String ytdlp = "yt-dlp";
    private String cookiesFile = "";

    public String getFfmpeg() {
        return ffmpeg;
    }

    public void setFfmpeg(String ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    public String getFfprobe() {
        return ffprobe;
    }

    public void setFfprobe(String ffprobe) {
        this.ffprobe = ffprobe;
    }

    public String getYtdlp() {
        return ytdlp;
    }

    public void setYtdlp(String ytdlp) {
        this.ytdlp = ytdlp;
    }

    public String getCookiesFile() {
        return cookiesFile;
    }

    public void setCookiesFile(String cookiesFile) {
        this.cookiesFile = cookiesFile;
    }
}
