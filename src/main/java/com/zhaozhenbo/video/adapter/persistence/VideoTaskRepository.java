package com.zhaozhenbo.video.adapter.persistence;

import com.zhaozhenbo.video.domain.Stage;
import com.zhaozhenbo.video.domain.TaskStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VideoTaskRepository extends JpaRepository<VideoTaskEntity, Long> {

    Optional<VideoTaskEntity> findByUserIdAndFingerprintAndParamsHash(
            long userId, String fingerprint, String paramsHash);

    /**
     * 原子抢占执行权。
     *
     * 这是消费幂等的关键：同一 taskId 被投递 3 次，只有第一次的
     * UPDATE 能匹配到 PENDING（或 FAILED），其余 affected rows = 0。
     * “先 select 再 if”在并发下会两个消费者同时通过，必须用数据库条件更新。
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update VideoTaskEntity t
               set t.status = :running, t.updatedAt = :now
             where t.id = :id and t.status = :expected
            """)
    int claim(@Param("id") long id,
              @Param("expected") TaskStatus expected,
              @Param("running") TaskStatus running,
              @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update VideoTaskEntity t
               set t.status = :status, t.stage = :stage, t.error = null, t.updatedAt = :now
             where t.id = :id
            """)
    int updateProgress(@Param("id") long id,
                       @Param("status") TaskStatus status,
                       @Param("stage") Stage stage,
                       @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update VideoTaskEntity t
               set t.status = :failed, t.error = :error, t.updatedAt = :now
             where t.id = :id
            """)
    int markFailed(@Param("id") long id,
                   @Param("failed") TaskStatus failed,
                   @Param("error") String error,
                   @Param("now") LocalDateTime now);

    @Query("""
            select t from VideoTaskEntity t
             where t.status = :status and t.updatedAt < :before
             order by t.id asc
            """)
    List<VideoTaskEntity> findStale(@Param("status") TaskStatus status,
                                    @Param("before") LocalDateTime before,
                                    Pageable pageable);
}
