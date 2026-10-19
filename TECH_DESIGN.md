# 技术设计文档（TECH_DESIGN.md）

## 概览

一个把长视频转成**带时间戳证据**的 Markdown 笔记的 Spring Boot 服务。
核心链路：接入素材 → 切段（保留时间轴）→ 受控 Agent 四步生成 → 证据校验 → 成稿。

设计上刻意分成两层价值：

- **业务价值**：笔记里每条要点都能点回原片那一秒，且引用是后端校验过的，不是模型说了算
- **工程价值**：长任务异步化、产物复用与幂等、阶段级 checkpoint、失败降级、可评测

## 技术选型

| 决策 | 选择 | 理由 | 放弃的方案 |
| --- | --- | --- | --- |
| 语言/框架 | **Spring Boot 4.1 + Java 25** | 本机 JDK 25 已就绪，骨架已编译通过 | Python（参考项目用的，但目标是练 Java） |
| 数据库 | **MySQL 8**（本机在跑） | 事务/索引/锁这些八股能真练到 | PostgreSQL：本机没有，为它装一套不值 |
| 缓存 | **Redis**（便携版，5MB） | 令牌桶限流、任务进度缓存、幂等辅助 | 不引入：会少掉一大块可讲的工程 |
| 异步 | **RocketMQ 5.5.1**（已在本机实测跑通，JDK 25） | 崩溃不丢消息、延迟重试由 broker 承担、天然削峰 | 纯线程池：进程崩溃丢任务、重试要自己实现延迟调度 |
| LLM | **HTTP 调用（OpenAI 兼容）** + `FakeLlm` 离线实现 | 可替换；测试不烧 token、结果可复现 | LangChain4j 全家桶：Spring 版本耦合，收益不明显 |
| 素材 | **字幕优先（SRT/VTT）**；无字幕走**云 ASR**（异步任务） | 字幕免费且准；云 ASR 免运维、弹性扩容、按量计费，适合线上部署 | 自建 whisper.cpp：模型和 GPU 绑在业务机上，扩容、隔离、故障恢复都差 |
| 分片/存储 | 本地文件系统（`data/`） | 单机项目，MinIO 属于过度设计 | MinIO：多一个进程，没换来可讲的工程点 |
| 容器 | **不做**（本轮决定） | 没有 Docker 环境，写了也验证不了 | Dockerfile 空挂：简历上会被追问 |

> **与现有简历的差异（诚实说明）**：RocketMQ 与 LangChain4j 本轮都用真的（见下）。
> 只有 **MinIO 不做了**（官方已归档、无可用二进制），改为本地文件 + `ObjectStoragePort` 抽象。
> 简历措辞要同步改成实际做到的，不能沿用旧说法。

### 为什么 ASR 不跑在业务进程里

这里的“本地”指把 whisper.cpp 作为子进程或 JNI 塞进 Spring Boot 实例。线上部署时问题很多：

| 问题 | 后果 |
| --- | --- |
| CPU/GPU 与 Web 请求争资源 | 一转写接口延迟就抖，严重时线程池被打满 |
| 容量固定 | 视频提交是突发的，业务机不能按转写峰值配置，闲时又浪费 |
| 多实例不一致 | 每个实例都要带一份模型文件，滚动发布、冷启动、健康检查都变复杂 |
| 故障不隔离 | 模型崩溃或 OOM 会把 API 一起带走 |
| 版本与合规 | 模型升级要重新发版；音频留在本机也不等于自动合规 |

如果将来真的自建，正确形态是**独立的 GPU 转写服务**：RocketMQ 投递 → 转写 worker 消费 →
结果写回 artifact，和 Web 进程分开部署、单独扩容。那不是“本地”，而是一个内部微服务。

本轮结论：`AsrPort` 只实现云适配器（阿里云/腾讯云二选一），自建转写服务不在范围内。

## 异步投递、幂等与参数指纹

### 参数指纹（params_hash）

幂等键是三元组 `(user_id, fingerprint, params_hash)`，三者各管一件事：

- `fingerprint`：输入是什么（视频内容 sha256）
- `params_hash`：怎么生成（pipeline 版本 + prompt 版本 + 模型 + 温度 + 风格 + 条数 + 语言）
- `user_id`：谁要的（任务归谁、能否计费、能否审计）

**规则**

1. 任何会改变输出的参数都必须进哈希；改了切段算法也要 bump `pipeline`
2. prompt 用**显式版本号**（`note.v3`），不用文本 hash——文本 hash 改一个标点就全量失效且不可读
3. **改 prompt 必须 bump 版本号**，否则会命中旧产物，生成结果与代码对不上
4. 序列化必须**字段顺序稳定**（record / TreeMap），禁止直接序列化 `HashMap`——
   否则"参数没改却新建了任务"这种玄学 bug 就会出现

**artifact 可以跨用户复用的原因**：命中的前提是 sha256 完全相同，即对方已持有同一份文件，
复用只省计算、不多给信息。真正的边界是：payload 不含原始文件名与用户标识；
绝不允许"命中指纹就返回别人的 task/note"。

**原则：数据库是事实源，MQ 只负责投递。** 两者职责分开，才不会出现"消息发了但库里没记录"或反之。

```
提交任务：
  1) INSERT video_task(status=PENDING)        ← 先落库
  2) 发送 topic=video-task 消息（key=taskId）
  3) 发送失败 → 任务保持 PENDING，由补偿任务每 30s 补发（本地消息表思想）

消费任务：
  1) 幂等前置：UPDATE video_task SET status='RUNNING'
       WHERE id=? AND status IN ('PENDING','RETRYING')   ← affected rows=0 说明已被处理
  2) 执行阶段（每阶段写 artifact + stage_log）
  3) 成功 → status=DONE；失败 → 计算重试次数
       次数 < 3 → 发送**延迟消息**（1s / 5s / 30s，对应 RocketMQ 延迟级别）
       ≥ 3     → status=FAILED，消息进死信队列，由人工/定时任务处理
```

**必须做的四件事**：

1. **消费幂等**：靠 `UPDATE ... WHERE status IN (...)` 的 affected rows 判断，不靠"先查再改"（并发下会双跑）
2. **延迟重试**：`rocketMQTemplate.syncSend(topic, msg, timeout, delayLevel)`，不要在消费者里 `sleep` 重试
3. **死信监控**：`%DLQ%video-task` 要有查询接口，否则失败消息永远躺在那里没人知道
4. **发送补偿**：定时扫 `status=PENDING AND updated_at < now-30s` 的任务补发，解决"库写成功、消息没发出去"

**本机启动**（两个进程，已在 D 盘就位）：

```powershell
$env:JAVA_HOME='D:\IntelliJ IDEA 2026.1\jbr'
$env:ROCKETMQ_HOME='D:\Code\tools\rmq\rocketmq-all-5.5.1-bin-release'
# 1) NameServer（256m 堆）
& "$env:ROCKETMQ_HOME\bin\mqnamesrv.cmd" -n 127.0.0.1:9876
# 2) Broker（512m 堆，配置见 conf\broker-local.conf）
& "$env:ROCKETMQ_HOME\bin\mqbroker.cmd" -n 127.0.0.1:9876 -c "$env:ROCKETMQ_HOME\conf\broker-local.conf"
# 3) 验证
& "$env:ROCKETMQ_HOME\bin\mqadmin.cmd" clusterList -n 127.0.0.1:9876
```

## 分层与模块边界

```
com.zhaozhenbo.video
├── api/            Controller + DTO（只做参数校验与转发）
├── application/    PipelineService：编排阶段、决策降级、推进状态机
├── domain/         Task / Stage / Note / NotePoint（纯对象 + 不变量）
├── port/           外部能力接口：CaptionPort / AsrPort / LlmPort / TaskQueuePort / RateLimitPort
├── adapter/
│   ├── caption/    SrtCaptionAdapter / VttCaptionAdapter / BilibiliCaptionAdapter
│   ├── asr/        NotConfiguredAsrAdapter（明确失败，不假装成功）
│   ├── llm/        DeepSeekLlmAdapter / FakeLlmAdapter（离线测试）
│   └── persist/    JPA Repository
├── pipeline/       各阶段实现（Fetch/Segment/Plan/Extract/Verify/Compose）
└── config/         线程池、超时、Redis、LLM 参数
```

**边界规则**：`domain` 不依赖 Spring；`port` 只有接口；`adapter` 才碰外部世界；
`application` 不写 SQL、不拼 prompt。换 ASR/LLM 供应商时只动 adapter。

## 数据模型

```sql
-- 任务：一次「一个视频出一份笔记」的请求
video_task(
  id            bigint pk auto_increment,
  source_type   varchar(16),      -- FILE / BILIBILI
  source_ref    varchar(512),     -- 文件路径或链接
  fingerprint   char(64),         -- 内容指纹（文件 sha256 / 视频 id）
  title         varchar(255),
  duration_ms   bigint,
  status        varchar(24),      -- 状态机见下
  stage         varchar(24),      -- 当前/最后成功阶段
  error         varchar(512),
  created_at    datetime(3),
  updated_at    datetime(3),
  unique key uk_fp (fingerprint)  -- 同一素材只允许一个活跃任务，配合幂等
)

-- 产物：按 (指纹, 阶段) 复用，命中即跳过该阶段的昂贵计算
video_artifact(
  id            bigint pk,
  fingerprint   char(64),
  stage         varchar(24),      -- CAPTION / SEGMENT / PLAN / EXTRACT / VERIFY
  payload       json,
  checksum      char(64),
  created_at    datetime(3),
  unique key uk_fp_stage (fingerprint, stage)
)

-- 笔记与要点：要点必须能回溯，所以单独建表而不是塞进一个大 JSON
note(
  id            bigint pk,
  task_id       bigint,
  markdown      longtext,
  model         varchar(64),
  prompt_version varchar(32),
  created_at    datetime(3)
)

note_point(
  id            bigint pk,
  note_id       bigint,
  seq           int,
  text          text,             -- 要点正文
  quote         text,             -- 引用原文片段
  start_ms      bigint,
  end_ms        bigint,
  overlap       decimal(4,3),     -- 与原文的重合度
  status        varchar(16),      -- GROUNDED / INFERRED / REJECTED
  key idx_note (note_id, seq)
)

-- 阶段日志：可观测性与"从哪一步恢复"的依据
stage_log(
  id            bigint pk,
  task_id       bigint,
  stage         varchar(24),
  status        varchar(16),      -- OK / FAILED / SKIPPED
  started_at    datetime(3),
  ended_at      datetime(3),
  duration_ms   bigint,
  tokens        int,
  error         varchar(512)
)
```

## 任务状态机

## 笔记输出模板（T5 按此实现，不做对比表）

```markdown
# {视频标题}

> 时长 1:23:45 · 模型 deepseek-chat · prompt note.v3 · 12 条要点（10 条可回溯）

## 内容总结
150~300 字，分 2~3 段：视频在讲什么问题、给了什么方法、结论是什么。
每段末尾标依据（如 [1.1][2.3]），确保总结能落到具体要点上，而不是自由发挥。

## 核心观点概述
一段话讲清视频主张，关键句加粗。 · 依据：[1.1][2.3][3.1]

## 一、{章节标题}
正文……

> 引用：「{原文}」 — [12:34–13:05] · overlap 0.87 · GROUNDED

## 时间轴
- 00:00 开场
- 12:34 索引下推

## 关键洞察
- {由 1.2 / 2.1 / 3.4 推出的结论} — 依据：3 条要点

## 未通过校验
- {引用对不上的要点} — overlap 0.12
```

**纪律**

1. **不做对比表**：A/B 两列的对应关系最容易被模型编造，读者无法核对
2. 表格、流程图一律不生成；时间轴用列表
3. 只有三类内容：可核对的要点、时间轴、**明确标注依据的推断**
4. `outline` 存章节 → 要点序号的映射，用于渲染目录与"本节几条 GROUNDED"
5. **一个视频只生成一次，生成即冻结**：`uk_note_user_video` 保证一份；
   note 与 note_point 一旦写入就只读，代码层不提供 update 路径（不可变数据）
6. 质量不达标时**根本不落 note**：例如 GROUNDED 占比 < 40% → 任务标 FAILED，
   让用户重试**任务**；不允许先把半成品写进去再覆盖

```
PENDING → FETCHING → SEGMENTING → PLANNING → EXTRACTING → VERIFYING → COMPOSING → DONE
                                                                    ↘ FAILED（可重试）
```

- 每个阶段成功后写 `stage_log` 并推进 `task.stage`（这就是 **checkpoint**）
- 重试时从 `task.stage` 的**下一个**阶段开始，前序产物从 `video_artifact` 命中
- 超步/超时：单任务总时长上限 10 分钟，单阶段上限见配置；超限 → `FAILED` 并写明阶段

## Agent 四步契约

每一步只接收结构化输入、只返回结构化输出（JSON），便于校验与测试。

**1) Planner**：给切段后的目录（每段 `id + start + 摘要`），产出提纲

```json
{ "outline": [ { "section": "开场与背景", "segmentIds": [1,2,3] } ] }
```

**2) Extractor**：给某一节对应的原文段落，产出要点 + 引用

```json
{
  "points": [
    {
      "text": "作者认为索引下推能把过滤下推到存储层",
      "quote": "MySQL 5.6 之后支持索引下推……减少回表",
      "segmentId": 7,
      "startMs": 812300,
      "endMs": 873900
    }
  ]
}
```

**3) Critic**：逐条判定"这条要点是否有原文支撑"

```json
{ "decisions": [ { "pointIndex": 0, "verdict": "GROUNDED", "reason": "引用命中" } ] }
```

**4) Composer**：把通过校验的要点组织成 Markdown（不改写事实，只做结构）。

**所有步骤都过 `LlmPort`**：`FakeLlmAdapter` 用固定规则产出合法 JSON，用于离线测试 ✅

## 证据校验算法（核心）

1. **归一化**：去空白、去标点、全半角统一、英文小写
2. **区间校验**：`quote` 必须能在 `[startMs, endMs]` 对应的原段里找到（防止跨段拼凑）
3. **重合度**：对归一化文本取 3-gram，算 `|交集| / |引用gram|`
4. **判定**：
   - `overlap ≥ 0.6` → `GROUNDED`
   - `0.3 ≤ overlap < 0.6` → 尝试同段内滑动窗口取最佳匹配；仍不足 → `INFERRED`（正文标注"模型推断"）
   - `< 0.3` 或区间不存在 → `REJECTED`（不进正文，进"未通过校验"列表）
5. 阈值全部配置化（`video.verify.*`），评测时可以扫阈值曲线

## 降级矩阵

| 失败点 | 降级动作 | 用户看到 |
| --- | --- | --- |
| 无字幕 | 走云 ASR：提交异步任务 → 轮询/回调 | 未配置或云端不可用 → 任务明确失败并说明原因（不伪造笔记） |
| 字幕格式异常 | 尝试解析可识别行，失败则报错 | "字幕解析失败：第 N 行格式不符合 SRT/VTT" |
| Planner 失败 | 退化为"按时间窗分节" | 笔记仍可生成，结构更平 |
| Extractor 部分失败 | 保留成功部分，记录失败段 | 笔记标注"第 X 段未能生成" |
| Critic 失败 | 全部要点标 `INFERRED` | 明确提示"本轮未完成证据校验" |
| Composer 失败 | 模板机械拼接要点 | 仍是可用 Markdown |

原则：**可选步骤失败不能毁掉主产物；但"没有依据的结论"绝不能混进正文充当事实。**

## 关键流程

**提交**：校验（类型/大小/时长）→ 算指纹 → 命中已完成任务则直接返回（幂等）→
落 `video_task(PENDING)` → 投递到 RocketMQ → **立即返回 taskId**（P95 ≤ 50ms）

**执行**：每个阶段 = `读 artifact → 命中则跳过 → 否则计算 → 写 artifact + stage_log → 推进`

**查询**：`GET /api/tasks/{id}` 返回状态、当前阶段、各阶段耗时、错误；
`GET /api/tasks/{id}/note` 返回 Markdown + 要点数组（带时间戳）

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/tasks` | 提交（multipart 文件 或 `{"sourceType":"BILIBILI","url":"..."}`） |
| GET | `/api/tasks/{id}` | 任务详情（状态机 + 阶段耗时） |
| GET | `/api/tasks/{id}/note` | 笔记（markdown + points） |
| GET | `/api/tasks/{id}/note.md` | 直接下载 Markdown |
| POST | `/api/tasks/{id}/retry` | 从最近 checkpoint 重试 |
| GET | `/api/eval` | 跑评测集，返回三项指标 |

## 测试策略

| 层 | 做法 | 说明 |
| --- | --- | --- |
| 单元 | 切段、证据校验、降级决策、状态机推进 | 纯函数，`mvn test` 秒级 |
| 集成 | `FakeLlmAdapter` + H2/MySQL，跑完整 pipeline | 不烧 token，结果确定 |
| 端到端 | 提交 SRT → 轮询 → 校验笔记与引用 | 断言"伪造引用会被剔除"这条 |
| 评测 | 30 条素材跑引用可回溯率 | 输出指标，用于简历数字 |

## 八股落点（写完代码要能各讲 3 分钟）

| 模块 | 能讲的考点 |
| --- | --- |
| 线程池 + 任务表 | 有界队列、拒绝策略、为什么不 `new Thread`、线程池参数怎么调 |
| 幂等 + 唯一索引 | 为什么唯一键比"先查再插"可靠、并发下谁先失败 |
| 产物复用 | 缓存命中率、缓存穿透、为什么用 DB 唯一键而不是纯 Redis |
| 状态机 + 事务 | 哪些写操作必须在一个事务里、`REQUIRES_NEW` 用在哪（stage_log 不该回滚） |
| Redis 令牌桶 | 限流的原子性、为什么用 Lua、和数据库计数的区别 |
| MySQL 索引 | `(note_id, seq)` 联合索引、覆盖索引、深分页 |

## 待确认

1. 字幕来源：v1 支持本地 SRT/VTT；无字幕走云 ASR（异步任务）。B 站字幕适配器作为可选。
2. 是否需要前端：需要（播放器跳转是卖点），但可以放到后端跑通之后。
