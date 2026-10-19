package com.zhaozhenbo.video.adapter.persistence;

import com.zhaozhenbo.video.pipeline.agent.NoteDraft;
import com.zhaozhenbo.video.pipeline.agent.VerifiedPoint;
import com.zhaozhenbo.video.port.NoteStorePort;
import com.zhaozhenbo.video.port.StoredNote;
import com.zhaozhenbo.video.port.StoredPoint;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * note / note_point 的 JDBC 实现。
 *
 * 没用 JPA：outline 是 JSON 列，直接按字符串写更可控；而且这里只有
 * 一次插入 + 批量插入，JdbcTemplate 比实体映射更直接。
 */
@Repository
public class JdbcNoteStore implements NoteStorePort {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcNoteStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void save(long userId, String fingerprint, long taskId, NoteDraft draft) {
        if (exists(userId, fingerprint)) {
            return; // 生成即冻结，已有笔记不覆盖
        }
        try {
            long noteId = insertNote(userId, fingerprint, taskId, draft);
            insertPoints(noteId, draft.points());
        } catch (DuplicateKeyException race) {
            // 并发下另一个任务先落了一篇，保持先到的那篇
        }
    }

    private boolean exists(long userId, String fingerprint) {
        Integer count = jdbc.queryForObject(
                "select count(*) from note where user_id = ? and video_fingerprint = ?",
                Integer.class, userId, fingerprint);
        return count != null && count > 0;
    }

    private long insertNote(long userId, String fingerprint, long taskId, NoteDraft draft) {
        String outlineJson;
        try {
            outlineJson = mapper.writeValueAsString(draft.outline());
        } catch (Exception e) {
            throw new IllegalStateException("outline 序列化失败", e);
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    insert into note
                        (user_id, video_fingerprint, task_id, summary, markdown, outline, model, prompt_version)
                    values (?, ?, ?, ?, ?, CAST(? AS JSON), ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, fingerprint);
            ps.setLong(3, taskId);
            ps.setString(4, draft.summary());
            ps.setString(5, draft.markdown());
            ps.setString(6, outlineJson);
            ps.setString(7, draft.model());
            ps.setString(8, draft.promptVersion());
            return ps;
        }, keyHolder);

        Map<String, Object> keys = keyHolder.getKeys();
        if (keys == null || keys.isEmpty()) {
            throw new IllegalStateException("note 插入后没有生成主键");
        }
        // H2 会把 id 和 created_at 都放进 generated keys，不能直接用 getKey()
        Object id = keys.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("id"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseGet(() -> keys.values().stream()
                        .filter(value -> value instanceof Number)
                        .findFirst()
                        .orElse(null));
        if (!(id instanceof Number number)) {
            throw new IllegalStateException("note 主键类型异常: " + keys);
        }
        return number.longValue();
    }

    private void insertPoints(long noteId, List<VerifiedPoint> points) {
        List<Object[]> batch = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            VerifiedPoint point = points.get(i);
            batch.add(new Object[]{
                    noteId,
                    i,
                    point.text(),
                    point.quote(),
                    point.section(),
                    point.startMs(),
                    point.endMs(),
                    point.overlap(),
                    point.status().name()
            });
        }
        jdbc.batchUpdate("""
                insert into note_point
                    (note_id, seq, text, quote, section, start_ms, end_ms, overlap, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, batch);
    }

    @Override
    public Optional<StoredNote> findByTask(long taskId, long userId, String fingerprint) {
        List<StoredNote> notes = jdbc.query("""
                select id, task_id, summary, markdown, model, prompt_version, created_at
                  from note
                 where task_id = ?
                    or (user_id = ? and video_fingerprint = ?)
                 order by (task_id = ?) desc
                 limit 1
                """, (rs, rowNum) -> new StoredNote(
                rs.getLong("id"),
                rs.getLong("task_id"),
                rs.getString("summary"),
                rs.getString("markdown"),
                rs.getString("model"),
                rs.getString("prompt_version"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                List.of()), taskId, userId, fingerprint, taskId);
        if (notes.isEmpty()) {
            return Optional.empty();
        }
        StoredNote note = notes.getFirst();
        List<StoredPoint> points = jdbc.query("""
                select seq, text, quote, section, start_ms, end_ms, overlap, status
                  from note_point where note_id = ? order by seq
                """, (rs, rowNum) -> new StoredPoint(
                rs.getInt("seq"),
                rs.getString("text"),
                rs.getString("quote"),
                rs.getString("section"),
                rs.getLong("start_ms"),
                rs.getLong("end_ms"),
                rs.getBigDecimal("overlap") == null ? 0 : rs.getBigDecimal("overlap").doubleValue(),
                rs.getString("status")), note.id());
        return Optional.of(new StoredNote(note.id(), note.taskId(), note.summary(), note.markdown(),
                note.model(), note.promptVersion(), note.createdAt(), points));
    }
}
