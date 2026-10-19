package com.zhaozhenbo.video.adapter.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StageLogRepository extends JpaRepository<StageLogEntity, Long> {

    List<StageLogEntity> findByTaskIdOrderByIdAsc(long taskId);
}
