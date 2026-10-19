# 变更记录

## 2026-10-19 — 视频 / 链接入口与分片上传

Tag：`milestone-video-2026-10-19`

### 已完成

- `POST /api/tasks` 支持视频（mp4/mkv/mov/webm）与字幕
- `POST /api/tasks/link` 支持视频链接
- `upload_session` / `upload_chunk`：8MB 分片、sha256、断点查询、合并校验
- `VideoCaptionSource`：ffmpeg 抽内嵌字幕；无字幕走 OpenAI 兼容云 ASR
- `LinkCaptionSource`：yt-dlp 只取字幕，支持 `YTDLP_COOKIES_FILE`
- 前端：链接 / 上传文件双入口、分片进度、失败后续传

### 验证

- 2MB 视频分 2 片上传；重复片幂等；合并后任务 DONE
- 浏览器视频上传进度 100%，笔记渲染正常，无控制台错误
- `mvn test`：46 项通过（1 项可选 MQ 冒烟默认跳过）

### 已知限制

- B 站实测可能 412，需要配置 cookies.txt
- 没有内嵌字幕且未配置 ASR 时任务会明确失败，不生成空笔记

### 回退

```powershell
git fetch --tags
git switch -c rollback/video milestone-video-2026-10-19
```

本地离线备份：

```text
D:\Code\backups\video-insight-video-2026-10-19.bundle
SHA256: 8CDD78E21369B28091E9749E1CDF3EAD611206744E1D02B126FE9E3C5F55EB73
```

## 2026-10-19 — 前端工作台（夜航玻璃拟态）

Tag：`milestone-frontend-2026-10-19`

### 已完成

- `src/main/resources/static/` 单页工作台，无前端构建链
- 上传/拖拽 SRT/VTT、标题输入、示例字幕、六阶段进度
- 笔记渲染：摘要、章节、要点、时间轴、原文展开、未通过校验
- 点击时间点更新“原片位置”读数，支持下载 Markdown
- 429 限流提示与 FAILED 断点重试
- Nocturne Glassmorphism：深墨夜景 + 光源光斑 + 7.5% 白玻璃 + 60/30px 模糊 +
  180% 饱和 + 方向性阴影 + 2.8% 噪点 + 单一香槟金强调色

### 验证

- `node --check` 通过
- 真实 MySQL + RocketMQ + FakeLLM：`/?demo=1` 自动跑完并渲染笔记
- Puppeteer 计算样式：桌面 blur(60px) saturate(1.8)，移动端 blur(30px)；
  1440 / 390 宽度 `scrollWidth == viewport`，无横向溢出
- 浏览器控制台无错误

### 回退

```powershell
git fetch --tags
git switch -c rollback/frontend milestone-frontend-2026-10-19
```

本地离线备份：

```text
D:\Code\backups\video-insight-frontend-2026-10-19.bundle
SHA256: 2B8EBFF3C5AA6899F46E6A4A941229E2E2570C33474BD62F03FAF638ECF4D1FE
```

## 2026-10-19 — T7 评测与限流

Tag：`milestone-t7-2026-10-19`

### 已完成

- `GET /api/eval`：`citationRecall`、`unsupportedRate`、`avgDurationMs`
- Redis Lua 令牌桶，按用户维度限制 `POST /api/tasks`
- 超限返回 429 + `Retry-After`；Redis 不可用时 fail-open
- 内存令牌桶用于测试/无 Redis 环境
- `tools/eval/run.ps1` + 3 个 SRT 素材

### 验证

- 44 项测试通过（1 项可选 MQ 冒烟默认跳过）
- 真实 Redis：容量 2，前两次 202，第三次 429，`Retry-After=9991`
- Redis key `rate:api:redis-test` 的 tokens/TTL 可查

### 回退

```powershell
git fetch --tags
git switch -c rollback/t7 milestone-t7-2026-10-19
```

本地离线备份：

```text
D:\Code\backups\video-insight-milestone-t7-2026-10-19.bundle
SHA256: C7F994DC9EF9AD29C31B5007FE299558414524C04F4E4864F84587B0D6873EC8
```

## 2026-10-19 — T6 API 与笔记落库

Tag：`milestone-t6-2026-10-19`

### 已完成

- `POST /api/tasks` multipart 上传 SRT/VTT，先落库再投递 RocketMQ
- 同用户 + 同文件 + 同参数幂等，重复提交返回同一个 taskId
- `GET /api/tasks/{id}`、`/note`、`/note.md`
- `POST /api/tasks/{id}/retry`：FAILED → PENDING，保留 stage 从断点继续
- `note` / `note_point` JDBC 落库；一个视频一篇笔记，生成即冻结
- 42 项测试通过（1 项可选 MQ 冒烟默认跳过）

### 验证

- 真 MySQL + RocketMQ + HTTP：上传 SRT → DONE → `note` / `note_point` 落库
- `/note` 返回首条要点 `GROUNDED`、`overlap=1.0`
- `/note.md` 返回完整 Markdown，内容与库中一致

### 回退

```powershell
git fetch --tags
git switch -c rollback/t6 milestone-t6-2026-10-19
```

本地离线备份：

```text
D:\Code\backups\video-insight-milestone-t6-2026-10-19.bundle
SHA256: 65E41E9392FD0CCCC468BC879F894F305E104C50752740CD83800CFC925E9200
```

## 2026-10-19 — T1–T5 里程碑

Tag：`milestone-t5-2026-10-19`

远程仓库：<https://github.com/Kaz-stein/video-insight>

关键提交：

- `af43104` — T1–T5 代码里程碑（tag / milestone 分支指向这里）
- `8f4df95` — 补充 README（文档提交，不改变代码里程碑）

本地离线备份：

```text
D:\Code\backups\video-insight-milestone-t5-2026-10-19.bundle
SHA256: 4819E540734D00E70F7B794CBCE36B9E30A80A9516798C0259110088210B89D4
```

需要离线恢复时：

```powershell
git clone D:\Code\backups\video-insight-milestone-t5-2026-10-19.bundle video-insight-restore
```

### 已完成

- **T1 工程骨架**：Spring Boot 4.1.1 + Java 25、MySQL schema、Actuator 健康检查
- **T2 任务状态机**：不可变 `TaskState`、阶段顺序校验、非法迁移抛可读异常
- **T3 字幕与切段**：SRT/VTT 双格式解析、BOM/乱序/多行/缺序号兼容、语义切段
- **T4 异步链路**：RocketMQ 生产/消费、条件更新幂等抢占、stage checkpoint、
  1s/5s/30s 延迟重试、死信、PENDING 补偿任务
- **T5 Agent 四步**：Planner / Extractor / Critic / Composer、FakeLLM、HTTP LLM 适配器、
  3-gram 证据校验、滑动窗口、降级矩阵、40% 可回溯率门槛
- **阶段产物**：`CAPTION/SEGMENT/PLAN/EXTRACT/VERIFY/NOTE` 落 `video_artifact`，
  键带 pipeline 版本（如 `SEGMENT@p1`），命中即跳过

### 验证

- `mvn test`：38 项，0 失败，1 跳过（真 MQ 冒烟默认关闭）
- 真 RocketMQ 冒烟：`MQ_SMOKE=true mvn -Dtest=RocketMqSmokeTest test` 通过
- MySQL JSON artifact：`MYSQL_IT=true mvn -Dtest=MysqlArtifactStoreIT test` 通过
- 手工端到端：MySQL 任务 + RocketMQ 消息 + FakeLLM，任务 `DONE`，六个 artifact 全部落库；
  NOTE 首条要点 `GROUNDED`，`overlap=1.0`

### 回退方式

先拉取标签：

```powershell
git fetch --tags
```

只查看这个里程碑（不改变当前分支）：

```powershell
git checkout milestone-t5-2026-10-19
```

从里程碑开一个回退分支：

```powershell
git switch -c rollback/t5 milestone-t5-2026-10-19
```

如果要把 `main` 强制拉回该点（**会丢失之后的提交，先建备份分支**）：

```powershell
git branch backup/before-rollback main
git reset --hard milestone-t5-2026-10-19
```

只想撤销某一次提交、保留历史，用：

```powershell
git revert <commit-sha>
```

### 下一里程碑

- T7：评测集、引用可回溯率/无依据率/平均耗时、Redis 令牌桶
- 前端：原生 HTML/CSS/JS + 播放器时间戳跳转
