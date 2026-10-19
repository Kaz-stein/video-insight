package com.zhaozhenbo.video.adapter.caption;

import com.zhaozhenbo.video.domain.CaptionCue;
import com.zhaozhenbo.video.port.CaptionSourcePort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 本地字幕文件适配器。只接受 .srt / .vtt，其他格式明确失败，
 * 不假装“读到了但内容为空”。
 */
public class LocalCaptionSource implements CaptionSourcePort {

    private final CaptionParser parser = new CaptionParser();

    @Override
    public List<CaptionCue> load(String sourceRef) {
        Path path = Path.of(sourceRef);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("字幕文件不存在: " + sourceRef);
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".srt") && !name.endsWith(".vtt")) {
            throw new IllegalArgumentException("v1 只支持 SRT/VTT 字幕文件: " + name);
        }
        try {
            return parser.parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读取字幕文件失败: " + sourceRef, e);
        }
    }
}
