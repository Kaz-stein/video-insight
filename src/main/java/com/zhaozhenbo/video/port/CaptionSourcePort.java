package com.zhaozhenbo.video.port;

import com.zhaozhenbo.video.domain.CaptionCue;

import java.util.List;

/** 字幕来源。v1 是本地 SRT/VTT；以后接 B 站/云 ASR 只加适配器。 */
public interface CaptionSourcePort {

    List<CaptionCue> load(String sourceRef);
}
