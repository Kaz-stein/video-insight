# 变更记录

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
