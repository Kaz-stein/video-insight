package com.zhaozhenbo.video.api.dto;

import com.zhaozhenbo.video.port.StoredNote;

import java.time.LocalDateTime;
import java.util.List;

public record NoteResponse(
        long noteId,
        long taskId,
        String summary,
        String markdown,
        String model,
        String promptVersion,
        LocalDateTime createdAt,
        List<PointResponse> points
) {
    public static NoteResponse from(StoredNote note) {
        return new NoteResponse(
                note.id(),
                note.taskId(),
                note.summary(),
                note.markdown(),
                note.model(),
                note.promptVersion(),
                note.createdAt(),
                note.points().stream().map(PointResponse::from).toList());
    }
}
