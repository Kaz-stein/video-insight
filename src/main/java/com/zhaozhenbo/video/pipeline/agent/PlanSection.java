package com.zhaozhenbo.video.pipeline.agent;

import java.util.List;

/** 一个章节：标题 + 它覆盖的段落序号（segment index，不是数据库 id）。 */
public record PlanSection(String title, List<Integer> segmentIndexes) {

    public PlanSection {
        title = title == null ? "" : title.strip();
        segmentIndexes = segmentIndexes == null ? List.of() : List.copyOf(segmentIndexes);
        if (title.isEmpty()) {
            throw new IllegalArgumentException("章节标题不能为空");
        }
    }
}
