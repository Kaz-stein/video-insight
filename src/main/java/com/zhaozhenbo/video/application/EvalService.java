package com.zhaozhenbo.video.application;

import com.zhaozhenbo.video.api.dto.EvalResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;

@Service
public class EvalService {

    private final JdbcTemplate jdbc;

    public EvalService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public EvalResponse evaluate() {
        Integer pointCount = jdbc.queryForObject("select count(*) from note_point", Integer.class);
        Integer grounded = jdbc.queryForObject(
                "select count(*) from note_point where status = 'GROUNDED'", Integer.class);
        Integer rejected = jdbc.queryForObject(
                "select count(*) from note_point where status = 'REJECTED'", Integer.class);
        Integer noteCount = jdbc.queryForObject("select count(*) from note", Integer.class);

        int points = pointCount == null ? 0 : pointCount;
        double citationRecall = points == 0 ? 0 : (grounded == null ? 0 : grounded) / (double) points;
        double unsupportedRate = points == 0 ? 0 : (rejected == null ? 0 : rejected) / (double) points;

        List<Timestamp[]> done = jdbc.query(
                "select created_at, updated_at from video_task where status = 'DONE'",
                (rs, rowNum) -> new Timestamp[]{
                        rs.getTimestamp("created_at"),
                        rs.getTimestamp("updated_at")});
        long avgDurationMs = (long) done.stream()
                .mapToLong(row -> Math.max(0,
                        Duration.between(row[0].toLocalDateTime(), row[1].toLocalDateTime()).toMillis()))
                .average()
                .orElse(0);

        return new EvalResponse(
                round(citationRecall),
                round(unsupportedRate),
                avgDurationMs,
                noteCount == null ? 0 : noteCount,
                points);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
