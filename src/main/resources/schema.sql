-- ============================================================================
-- 表结构：由应用启动时执行（dev）。生产应换成 Flyway/Liquibase 做版本化迁移。
--
-- 三个刻意的选择，面试常问：
--   1) 主键用 BIGINT AUTO_INCREMENT 而不是 UUID
--      → InnoDB 的聚簇索引按主键物理排序，自增主键是"顺序追加"，UUID 是"随机插入"，
--        后者会造成页分裂与索引碎片；代价是分库分表时不好生成，本项目用不到。
--   2) 时间列统一 datetime(3)（毫秒精度）
--      → 任务的阶段耗时是毫秒级，datetime(0) 会把 1.2s 和 1.9s 都记成 1s。
--   3) 数据列不用外键约束
--      → 本项目按"聚合根"划分（task / note / artifact 各自独立），外键会把写路径绑死，
--        真正的一致性靠应用层 + 唯一键保证；这也是大多数互联网项目的做法。
-- ============================================================================

CREATE TABLE IF NOT EXISTS video_task (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '任务 ID',
    user_id      BIGINT       NOT NULL DEFAULT 0 COMMENT 'v1 单用户固定 0；多用户时存真实用户 ID',
    source_type  VARCHAR(16)  NOT NULL COMMENT 'FILE / BILIBILI',
    source_ref   VARCHAR(1024) NOT NULL COMMENT '文件路径或视频链接（Windows 长路径可能超过 512）',
    fingerprint  CHAR(64)     NOT NULL COMMENT '内容指纹 sha256，用于幂等与产物复用',
    params_hash  CHAR(64)     NOT NULL COMMENT '生成参数指纹（长度/风格/模型/prompt 版本）',
    title        VARCHAR(255)          DEFAULT NULL,
    duration_ms  BIGINT                DEFAULT NULL,
    status       VARCHAR(24)  NOT NULL COMMENT 'PENDING/RUNNING/DONE/FAILED...',
    stage        VARCHAR(24)           DEFAULT NULL COMMENT '最后成功/正在执行的阶段',
    error        TEXT                  DEFAULT NULL COMMENT '用 TEXT：异常消息经常超过 512，截断会丢关键信息',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 幂等键必须是「谁 + 什么内容 + 什么参数」三元组：
    --   同用户同视频同参数 → 复用（重复提交不重复烧钱）
    --   同用户同视频不同参数 → 新任务（支持"再来一版精简的"）
    --   不同用户同一视频     → 各自独立（不能撞唯一键，更不能看到别人的笔记）
    UNIQUE KEY uk_task_user_fp_params (user_id, fingerprint, params_hash),
    -- "我的任务列表"按用户 + 时间倒序（走最左前缀 user_id）
    KEY idx_task_user_time (user_id, created_at),
    -- 按状态扫待调度/待补偿的任务
    KEY idx_task_status_time (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='视频解析任务';

CREATE TABLE IF NOT EXISTS video_artifact (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    fingerprint  CHAR(64)    NOT NULL,
    stage        VARCHAR(24) NOT NULL COMMENT 'CAPTION/SEGMENT/PLAN/EXTRACT/VERIFY',
    payload      JSON        NOT NULL COMMENT '该阶段产物，命中即跳过重算',
    checksum     CHAR(64)             DEFAULT NULL,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- (指纹, 阶段) 唯一：同一素材的同一阶段只存一份。
    --
    -- 为什么这里可以不带 user_id（跨用户复用）：
    --   命中复用的前提是 sha256 完全相同，也就是对方已经持有同一份文件，
    --   内容本来就在他手里；复用只是替他省掉重复计算，并未多给出任何信息。
    --
    -- 真正的隐私边界在别处，不在"复用"：
    --   1) payload 里绝不能存原始文件名、user_id、上传者信息（文件名常含人名）
    --   2) 复用的只能是产物，绝不能"命中指纹就把别人的 task/note 返回"
    --   3) 不对外提供"按 fingerprint 查询是否存在/取产物"的接口
    UNIQUE KEY uk_artifact_fp_stage (fingerprint, stage)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='阶段产物（可复用）';

CREATE TABLE IF NOT EXISTS note (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    user_id        BIGINT      NOT NULL DEFAULT 0 COMMENT 'v1 单用户固定 0',
    video_fingerprint CHAR(64) NOT NULL COMMENT '所属视频内容指纹',
    task_id        BIGINT      NOT NULL,
    summary        TEXT                 DEFAULT NULL COMMENT '内容总结：150~300 字，覆盖全部要点',
    markdown       MEDIUMTEXT  NOT NULL COMMENT 'MEDIUMTEXT(16MB) 足够；LONGTEXT 是 4GB，属于过度',
    outline        JSON                 DEFAULT NULL COMMENT '章节结构 [{seq,title,pointSeqs}]，用于目录与"按章跳转"',
    model          VARCHAR(64)          DEFAULT NULL,
    prompt_version VARCHAR(32)          DEFAULT NULL,
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 产品决策：一个视频只生成一次，生成即冻结。没有"重新生成"，也没有版本历史。
    -- 旧笔记保持原样（它记录了当时的模型与 prompt 版本），想重做只能先删再提交。
    UNIQUE KEY uk_note_user_video (user_id, video_fingerprint),
    KEY idx_note_task (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='笔记正文';

CREATE TABLE IF NOT EXISTS note_point (
    id       BIGINT       NOT NULL AUTO_INCREMENT,
    note_id  BIGINT       NOT NULL,
    seq      INT          NOT NULL COMMENT '展示顺序',
    text     TEXT         NOT NULL COMMENT '要点正文',
    quote    TEXT                  DEFAULT NULL COMMENT '引用原文片段',
    section  VARCHAR(64)           DEFAULT NULL COMMENT '所属章节标题（冗余，便于按节统计证据覆盖率）',
    start_ms BIGINT                DEFAULT NULL,
    end_ms   BIGINT                DEFAULT NULL,
    overlap  DECIMAL(4, 3)         DEFAULT NULL COMMENT '引用与原文的重合度 0.000~1.000',
    status   VARCHAR(16)  NOT NULL COMMENT 'GROUNDED/INFERRED/REJECTED',
    PRIMARY KEY (id),
    -- 改唯一键：同一篇笔记里 seq 不能重复，否则前端列表会出现两条同序要点
    UNIQUE KEY uk_point_note_seq (note_id, seq),
    KEY idx_point_status (status),
    CONSTRAINT ck_point_overlap CHECK (overlap IS NULL OR (overlap >= 0 AND overlap <= 1)),
    CONSTRAINT ck_point_span CHECK (start_ms IS NULL OR end_ms IS NULL OR end_ms >= start_ms)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='笔记要点（证据链）';

CREATE TABLE IF NOT EXISTS stage_log (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    task_id     BIGINT      NOT NULL,
    stage       VARCHAR(24) NOT NULL,
    status      VARCHAR(16) NOT NULL COMMENT 'OK/FAILED/SKIPPED',
    started_at  DATETIME(3) NOT NULL,
    ended_at    DATETIME(3)          DEFAULT NULL,
    duration_ms BIGINT               DEFAULT NULL,
    tokens      INT                  DEFAULT NULL,
    attempt     INT         NOT NULL DEFAULT 1 COMMENT '第几次尝试：同一阶段可能失败重试多次',
    error       TEXT                 DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_log_task (task_id, id),
    -- 同一任务同一阶段的第 N 次尝试只能有一条记录，否则无法准确统计重试次数
    UNIQUE KEY uk_log_task_stage_attempt (task_id, stage, attempt)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='阶段日志（可观测性）';
