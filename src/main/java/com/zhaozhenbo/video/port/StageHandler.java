package com.zhaozhenbo.video.port;

import com.zhaozhenbo.video.domain.Stage;
import com.zhaozhenbo.video.application.TaskContext;

/**
 * 单个流水线阶段的处理器。
 *
 * 每个阶段只做一件事，失败就抛异常；重试、日志、推进阶段由
 * TaskPipelineService 统一负责，阶段实现里不写调度逻辑。
 */
public interface StageHandler {

    Stage stage();

    void execute(TaskContext context) throws Exception;
}
