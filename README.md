# Video Insight

把长视频变成**每条要点都能回跳原片时间点**的 Markdown 笔记。

> 当前状态：视频链接 / 视频文件 / 字幕三种入口已接通；视频优先抽内嵌字幕，无字幕走云 ASR；
> 大文件走 8MB 分片上传与断点续传。播放器同步与画面 OCR 还在路线图上。

## 为什么做这个

普通视频总结最大的问题不是“写得不顺”，而是**无法核对**：模型说视频里讲了某件事，
你没法快速确认它是不是编的。这个项目把“可回溯”做成后端可验算的约束：

- 每条要点必须带 `[startMs, endMs]`
- 引用必须能在该时间区间的原文里找到
- 用 3-gram 重合度计算证据强度，低于阈值直接剔除
- Critic 再做一层语义审查；Critic 不可用时全部降级为“模型推断”

## 界面

启动应用后打开 <http://localhost:8080/>。页面使用夜航玻璃拟态：深墨夜景、柔和光源、无色玻璃、香槟金单一强调色，以及一条可点击的“证据光轨”。

![桌面端](docs/screenshots/desktop.png)

![移动端](docs/screenshots/mobile.png)

想直接看一次完整流程，打开 <http://localhost:8080/?demo=1>，页面会自动提交内置示例字幕。

## 当前能力

- SRT / VTT 字幕解析：BOM、乱序、多行、缺序号、`\r\n` 混用
- 时间窗 + 语义边界切段：软上限 75s、硬上限 120s、停顿 800ms、短段合并
- Agent 四步：Planner / Extractor / Critic / Composer
- 证据校验：`GROUNDED / INFERRED / REJECTED` 三档 + 滑动窗口
- 质量门槛：可回溯率低于 40% 不落库
- RocketMQ 异步：幂等抢占、阶段 checkpoint、1s/5s/30s 重试、死信、补偿投递
- 阶段产物落 MySQL `video_artifact`（JSON），键带 pipeline 版本
- FakeLLM 离线测试 + OpenAI 兼容 HTTP 适配器（DeepSeek / Qwen / GLM）
- HTTP API：上传字幕、查询任务、取笔记 JSON、下载 Markdown、失败重试
- 视频链接：B 站走公开 API（pagelist/player/playurl），YouTube 走 yt-dlp；都不下载整段视频
- 视频文件：ffmpeg 抽内嵌字幕；没有字幕时调用 OpenAI 兼容的云 ASR
- 分片上传：8MB 分片、分片 sha256、断点查询、整体合并校验
- 评测接口：引用可回溯率、无依据率、平均端到端耗时
- Redis 令牌桶限流：Lua 原子扣减，超限返回 429 + `Retry-After`

## 架构

```text
SRT/VTT
  │
  ▼
FETCHING ──► SEGMENTING ──► PLANNING ──► EXTRACTING ──► VERIFYING ──► COMPOSING
   │             │             │             │             │             │
   └─────────────┴─────────────┴──── video_artifact ───────┴─────────────┘
                                      (MySQL JSON)

RocketMQ: video-task 主题
  消费幂等：UPDATE ... WHERE status = 'PENDING' / 'FAILED'
  失败重试：1s → 5s → 30s → 死信
  PENDING 超过 60s：补偿任务重新投递
```

## 技术栈

- Java 25 + Spring Boot 4.1.1
- MySQL 8（JPA + `JdbcTemplate` 读写 JSON artifact）
- RocketMQ 5.5.1（经典客户端）
- Redis（令牌桶；无 Redis 时可用内存实现）
- 前端：原生 HTML / CSS / JavaScript，无构建链
- ffmpeg / yt-dlp（视频与链接解析）
- 云 ASR：OpenAI 兼容 `/v1/audio/transcriptions`
- Jackson 3
- JUnit 5 + AssertJ + H2（测试）

## 快速开始

### 1. 数据库

```sql
CREATE DATABASE video_insight CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

应用启动时会执行 `src/main/resources/schema.sql`，建五张表。

### 2. RocketMQ

```powershell
$env:ROCKETMQ_HOME='D:\Code\tools\rmq\rocketmq-all-5.5.1-bin-release'
bin\mqnamesrv.cmd -n 127.0.0.1:9876
bin\mqbroker.cmd -n 127.0.0.1:9876 -c conf\broker-local.conf
```

### 3. 环境变量

```powershell
$env:DB_PASSWORD='你的 MySQL 密码'
$env:MQ_ENABLED='true'
$env:LLM_PROVIDER='fake'          # 本地演示；真实模型用 http
$env:FFMPEG_PATH='...\ffmpeg.exe'
$env:YTDLP_PATH='...\yt-dlp.exe'
# $env:YTDLP_COOKIES_FILE='...\cookies.txt'   # YouTube 需要时
# $env:ASR_ENABLED='true'
# $env:ASR_BASE_URL='https://api.openai.com'
# $env:ASR_API_KEY='...'
# $env:ASR_MODEL='whisper-1'
# $env:ASR_LANGUAGE='zh'
$env:RATE_LIMIT_MODE='memory'     # 有 Redis 时用 redis
# $env:RATE_LIMIT_CAPACITY='10'
# $env:RATE_LIMIT_REFILL='2'
# $env:LLM_BASE_URL='https://api.deepseek.com'
# $env:LLM_API_KEY='...'
# $env:LLM_MODEL='deepseek-chat'
```

ASR 是 **OpenAI 兼容**的 `/v1/audio/transcriptions` 接口，推荐用环境变量配置：

```powershell
$env:ASR_ENABLED='true'
$env:ASR_BASE_URL='https://api.openai.com'   # 或兼容网关
$env:ASR_API_KEY='sk-...'
$env:ASR_MODEL='whisper-1'
$env:ASR_LANGUAGE='zh'
# $env:ASR_MAX_AUDIO_MB='100'
```

配置后必须重启应用。视频没有内嵌字幕时，后端用 ffmpeg 抽 16kHz 单声道 MP3
（48kbps，长音频体积更小），再调用该接口。阿里云/腾讯云原生 SDK 不是这个协议，
需要一个 OpenAI 兼容网关，或另写 `AsrPort` 适配器。

### 4. 测试与运行

```powershell
$env:JAVA_HOME='D:\IntelliJ IDEA 2026.1\jbr'
$env:Path = "C:\Windows\System32;C:\Windows;" + $env:Path

# 单元 / 集成测试
& 'D:\IntelliJ IDEA 2026.1\plugins\maven\lib\maven3\bin\mvn.cmd' -B test

# 打包运行
& 'D:\IntelliJ IDEA 2026.1\plugins\maven\lib\maven3\bin\mvn.cmd' -B -DskipTests package
java -jar target\video-insight-0.0.1-SNAPSHOT.jar
```

上传字幕并生成笔记：

```powershell
$form = @{
  file  = Get-Item 'data\demo.srt'
  title = '线程池讲解'
}
$task = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/tasks' -Method Post -Form $form
$task.id

# 轮询状态，DONE 后取笔记
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/tasks/$($task.id)/note"
Invoke-WebRequest -Uri "http://127.0.0.1:8080/api/tasks/$($task.id)/note.md" -OutFile note.md
```

接口入口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/tasks` | multipart 上传视频（mp4/mkv/mov/webm）或字幕（srt/vtt） |
| POST | `/api/tasks/link` | JSON `{url,title}`，解析视频链接 |
| POST | `/api/uploads` | 创建 8MB 分片上传会话 |
| PUT | `/api/uploads/{id}/chunks/{index}` | 上传分片，`X-Chunk-SHA256` 校验 |
| GET | `/api/uploads/{id}` | 查询已上传分片，断点续传 |
| POST | `/api/uploads/{id}/complete` | 合并并创建任务 |

评测：

```powershell
.\tools\eval\run.ps1 -BaseUrl 'http://127.0.0.1:8080'
```

可选的真中间件验证：

```powershell
$env:MQ_SMOKE='true'; mvn -Dtest=RocketMqSmokeTest test
$env:MYSQL_IT='true'; mvn -Dtest=MysqlArtifactStoreIT test
```

## 证据校验

| 3-gram 重合度 | 结果 | 处理 |
| --- | --- | --- |
| ≥ 0.6 且 Critic 通过 | `GROUNDED` | 进正文，可回跳原片 |
| 0.3 ~ 0.6 | 同区间滑动窗口再找 | 仍 < 0.6 → `INFERRED`，标注“模型推断” |
| < 0.3 或区间不存在 | `REJECTED` | 不进正文，进“未通过校验” |
| Critic 不可用 | 非 REJECTED 全部降为 `INFERRED` | 不把未审内容当事实 |

## 测试状态

- `mvn test`：46 项通过，0 失败，1 项可选 MQ 冒烟默认跳过
- 真 RocketMQ 冒烟：通过
- MySQL JSON artifact 集成测试：通过
- 手工端到端：任务 `DONE`，六个 artifact 落库，NOTE 首条要点 `GROUNDED / overlap=1.0`

## 回退

里程碑固定为：

- 分支：`milestone/frontend-2026-10-19`
- 标签：`milestone-frontend-2026-10-19`

```powershell
git fetch --tags
git switch -c rollback/frontend milestone-frontend-2026-10-19
```

把 `main` 强制拉回该点前请先备份分支，详见 [CHANGELOG.md](CHANGELOG.md)。

## Roadmap

- 前端：播放器时间戳同步（页面和交互已完成）
- 输入：画面 OCR（后续）

## 说明

视频内嵌字幕可直接解析；没有内嵌字幕时需要配置云 ASR。
B 站链接走公开 API，不再依赖 yt-dlp 的 412 链路；如果视频没有公开 CC 字幕，会明确提示配置 ASR。
YouTube 链接仍走 yt-dlp，必要时配置 `YTDLP_COOKIES_FILE`；画面 OCR 还没实现。
