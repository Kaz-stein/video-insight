package com.zhaozhenbo.video.port;

import java.time.LocalDateTime;
import java.util.List;

public record StoredNote(
        long id,
        long taskId,
        String summary,
        String markdown,
        String model,
        String promptVersion,
        LocalDateTime createdAt,
        List<StoredPoint> points
) {
    public StoredNote {
        points = points == null ? List.of() : List.copyOf(points);
    }
}
