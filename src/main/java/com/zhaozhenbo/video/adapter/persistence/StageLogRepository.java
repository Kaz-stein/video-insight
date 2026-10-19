package com.zhaozhenbo.video.adapter.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.zhaozhenbo.video.domain.Stage;

import java.util.List;

public interface StageLogRepository extends JpaRepository<StageLogEntity, Long> {

    List<StageLogEntity> findByTaskIdOrderByIdAsc(long taskId);

    @Query("select coalesce(max(l.attempt), 0) from StageLogEntity l where l.taskId = :taskId")
    int findMaxAttempt(@Param("taskId") long taskId);

    long countByTaskIdAndStageAndStatus(long taskId, Stage stage, String status);
}
