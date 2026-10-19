package com.zhaozhenbo.video.port;

import com.zhaozhenbo.video.pipeline.agent.NoteDraft;

import java.util.Optional;

/**
 * 笔记持久化端口。一个视频只允许一篇笔记（uk_note_user_video），
 * 生成即冻结；重复写入直接忽略。
 */
public interface NoteStorePort {

    void save(long userId, String fingerprint, long taskId, NoteDraft draft);

    /**
     * 按任务找笔记；同一视频只允许一篇，所以也接受 user + fingerprint 回退。
     */
    Optional<StoredNote> findByTask(long taskId, long userId, String fingerprint);
}
