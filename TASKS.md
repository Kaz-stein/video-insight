# 实施任务（按依赖顺序，每个都可独立验证）

> 原则：先让主链路跑通（T1–T6），再谈评测与优化（T7）。
> 每个任务都要能单独跑测试，不允许"等全部写完再验证"。

## T1 骨架与数据层 ✅

- 目标：工程能启动，MySQL 建表成功，健康检查可访问
- 范围：`application.yml`、`schema.sql`、JPA 实体、`VideoInsightApplication`
- 验收：`mvn test` 通过；启动后 `GET /actuator/health` 返回 UP；四张表存在

## T2 领域模型与状态机 ✅

- 目标：任务状态迁移有明确规则，非法迁移直接抛可读异常
- 范围：`domain/`（TaskStatus、Stage、Note、NotePoint + 不变量）
- 验收：单测覆盖"完成后不能再回退""失败后只能重试到下一个阶段"等 6 条规则

## T3 字幕接入与切段 ✅

- 目标：能读取 SRT/VTT，切成带时间轴的段，段边界可与播放器对齐
- 范围：`adapter/caption/`、`pipeline/Segmenter`、`TextNormalizer`
- 验收：单测覆盖正常 SRT、缺时间轴、乱序、超长单段（> 3 分钟）四种输入；
  切段结果总时长与字幕总时长误差 < 1 秒

## T4 异步执行（RocketMQ）与 checkpoint ✅

- 目标：提交立即返回；消息驱动执行；失败走延迟重试，超限进死信；可从最近成功阶段恢复
- 范围：`port/TaskQueuePort` + `adapter/mq/RocketMqTaskQueueAdapter`、Producer/Consumer、
  `PipelineService`、`stage_log` 写入、发送补偿定时任务
- 验收：
  1. 提交接口 P95 ≤ 50ms（落库 + 发消息，不等执行）
  2. 消费幂等：同一 taskId 重复投递 3 次，只执行一次（用计数器验证）
  3. 第 3 阶段失败后重试，前两阶段不再执行（用计数器验证）
  4. 重试超 3 次进 `%DLQ%video-task`，且任务标 FAILED
  5. 人为让发送失败 → 30s 内补偿任务补发成功

实现说明：

- 幂等抢占：首次消息 `UPDATE ... WHERE status='PENDING'`，重试消息 `WHERE status='FAILED'`，
  affected rows = 0 直接跳过
- 重试节奏 1s / 5s / 30s；第 4 次仍失败投递到 `video.mq.dlq-topic`（默认 `%DLQ%video-task`，
  broker 不允许写系统 Topic 时回退 `video-task-dlq`）
- `stage_log` 每个阶段每次尝试一行，`(task_id, stage, attempt)` 唯一
- 补偿任务扫 `PENDING` 超过 60s 的任务重新投递，重复投递由抢占保证只执行一次
- 真 broker 冒烟测试：`MQ_SMOKE=true mvn -Dtest=RocketMqSmokeTest test`
  （需先预创建 `video-task-smoke`，且 RocketMQ 所在盘使用率必须 < 90%）

## T5 Agent 四步 + 证据校验 + 降级 ✅

- 目标：四个步骤有明确 JSON 契约；证据校验能剔除伪造引用；降级矩阵生效
- 范围：`pipeline/Planner|Extractor|Critic|Composer`、`adapter/llm/FakeLlmAdapter`、
  `EvidenceVerifier`
- 验收：单测覆盖 overlap ≥0.6 / 0.3–0.6 / <0.3 三档；集成测试覆盖
  "Critic 失败 → 全部标 INFERRED"与"Composer 失败 → 机械拼接仍可用"

实现说明：

- `LlmPort` + `FakeLlmAdapter`（离线、可注入失败）+ `HttpLlmAdapter`（OpenAI 兼容）
- `EvidenceVerifier`：3-gram 多重集重合度；`<0.3` REJECTED，`0.3~0.6` 同区间滑动窗口，
  仍不足则 INFERRED，`≥0.6` 且 Critic 通过才 GROUNDED
- `Planner` 失败按每 5 段降级；`Extractor` 按章节隔离，部分章节失败保留其余；
  `Critic` 不可用全部降为 INFERRED；`Composer` 缺模板块/失败时机械拼接
- `NoteDraft` 质量门槛：GROUNDED 占比 < 40% 直接失败，不落“看起来像笔记”的垃圾
- 阶段产物键带 pipeline 版本（`SEGMENT@p1`），改算法 bump 版本即可避免旧产物污染
- 端到端已验证：MySQL 任务 + RocketMQ 消息 + FakeLLM，SRT 跑完六阶段，任务 DONE，
  六个 artifact 全部落库，NOTE 里第一条要点 overlap=1.0 / GROUNDED
- MySQL JSON 列验证：`MYSQL_IT=true mvn -Dtest=MysqlArtifactStoreIT test`

## T6 API 与端到端 ✅

- 目标：HTTP 层可用，能提交、查询、取 Markdown
- 范围：`api/` 控制器、DTO、异常处理器
- 验收：端到端测试（`FakeLlm`）走完提交 → 轮询 → 取笔记；
  **含一条"伪造引用被剔除"的断言**；`GET /note.md` 下载内容与库中 markdown 一致

实现说明：

- `POST /api/tasks`：multipart 上传 SRT/VTT，算 sha256 + params_hash，先落库再投递 MQ
- 幂等：同用户 + 同文件 + 同参数直接返回已有 taskId
- `GET /api/tasks/{id}`、`GET /api/tasks/{id}/note`、`GET /api/tasks/{id}/note.md`
- `POST /api/tasks/{id}/retry`：只允许 FAILED，保留 stage，从断点继续
- `note` / `note_point` 用 JDBC 落库；一个视频一篇笔记，生成即冻结
- 4 个 MockMvc 用例：完整流程、重复提交幂等、笔记未生成 409、FAILED 重试
- 真 MySQL + RocketMQ + HTTP 验证：上传 SRT → task DONE → note/note_point 落库 →
  `/note` 返回 GROUNDED / overlap=1.0 → `/note.md` 返回 Markdown

## T7 评测与限流 ✅

- 目标：一条命令输出三项指标；接口限流生效
- 范围：`tools/eval`（评测集 + 跑分脚本）、Redis 令牌桶
- 验收：`GET /api/eval` 返回 `{ citationRecall, unsupportedRate, avgDurationMs }`；
  超过限额返回 429 且带 `Retry-After`

实现说明：

- `GET /api/eval`：按 `note_point` 聚合可回溯率/无依据率，按 DONE 任务聚合平均耗时
- `RedisTokenBucketRateLimiter`：Lua 脚本在 Redis 内原子完成读-算-写，避免并发超发
- `RateLimitFilter`：只限 `POST /api/tasks`；超限返回 429 + `Retry-After`；Redis 不可用时 fail-open
- 无 Redis 环境用内存令牌桶，测试可确定性验证
- `tools/eval/run.ps1` + 3 个 SRT 素材，一条命令上传并输出指标
- 验证：真实 Redis 容量 2，前两次 202，第三次 429，`Retry-After=9991`，
  Redis key 的 tokens/TTL 可查；44 项测试通过（1 项可选 MQ 冒烟默认跳过）

## T8 前端工作台 ✅

- 目标：把 API 能力变成可用的单页工作台，视觉严格遵循 Nocturne Glassmorphism
- 范围：`src/main/resources/static/`（index.html + app.css + app.js）
- 验收：
  - 上传/拖拽 SRT/VTT、标题输入、示例字幕
  - 六阶段进度、DONE 后渲染摘要/章节/要点/时间轴
  - 点击时间显示原片位置，展开原文，下载 .md
  - 429 限流提示、FAILED 断点重试
  - 1440 / 390 宽度无横向溢出；浏览器控制台无错误
  - 计算样式：玻璃 7.5% 白、blur 60/30px、saturate 180%、香槟金 CTA

## 仍不在本轮

ASR 真机接入 · 画面 OCR · B 站链接抓取 · 邮件投递 · Docker · 播放器同步
