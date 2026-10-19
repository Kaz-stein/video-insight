package com.zhaozhenbo.video.port;

import com.zhaozhenbo.video.domain.CaptionCue;

import java.nio.file.Path;
import java.util.List;

/** 音频转字幕。生产用云 ASR；接口保留，未来可以换独立 GPU worker。 */
public interface AsrPort {

    List<CaptionCue> transcribe(Path audioFile);
}
