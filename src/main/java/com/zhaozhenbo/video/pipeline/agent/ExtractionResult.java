package com.zhaozhenbo.video.pipeline.agent;

import java.util.List;

/**
 * 部分成功的结果：成功的要点继续走校验，失败的章节记录下来给用户看。
 * 这比“一段失败整篇重来”更符合长视频的成本模型。
 */
public record ExtractionResult(List<ExtractedPoint> points, List<String> failedSections) {

    public ExtractionResult {
        points = points == null ? List.of() : List.copyOf(points);
        failedSections = failedSections == null ? List.of() : List.copyOf(failedSections);
    }
}
