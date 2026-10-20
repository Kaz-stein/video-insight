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
- 分片上传：8MB 分片、3 路并发、分片 sha256、断点续传、合并时可选整文件 sha256 校验
- 任务历史：最近 8 条任务列表，点开即载入笔记；刷新页面自动回到上次看的任务
- 笔记成稿：`ComposerPrompt` v13，先判型（并列 / 递进）再抽信息清单，最后按模板成稿
- 专名还原：B 站任务回填真实标题作为锚点，ASR 听坏的英文专名按标题还原并标注“原文读作 X”
- 前端渲染：笔记 Markdown 直接渲染（标题 / 列表 / 代码块 / 表格），正文时间轴可点击跳回 B 站对应秒
- 评测接口：引用可回溯率、无依据率、平均端到端耗时
- Redis 令牌桶限流：Lua 原子扣减，超限返回 429 + `Retry-After`
- 本地无 broker 模式：`MQ_ENABLED=false` 时用虚拟线程消费内存队列，生产仍走 RocketMQ

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
# $env:ASR_PROVIDER='siliconflow'
# $env:ASR_BASE_URL='https://api.siliconflow.cn/v1'
# $env:ASR_API_KEY='...'
# $env:ASR_MODEL='FunAudioLLM/SenseVoiceSmall'
# $env:ASR_LANGUAGE='zh'
$env:RATE_LIMIT_MODE='memory'     # 有 Redis 时用 redis
# $env:RATE_LIMIT_CAPACITY='10'
# $env:RATE_LIMIT_REFILL='2'
# $env:LLM_BASE_URL='https://api.deepseek.com'
# $env:LLM_API_KEY='...'
# $env:LLM_MODEL='deepseek-chat'
```

ASR 走 **OpenAI 兼容**的 `/v1/audio/transcriptions` 接口，两个适配器：

- `ASR_PROVIDER=siliconflow`：SenseVoice，只返回纯文本没有句级时间戳，服务端按 60 秒切片、3 路并发转写，时间精度 ±60 秒
- `ASR_PROVIDER=openai`：请求 `verbose_json` 并解析 `segments`，能拿到句级时间戳，适合对可回跳精度要求高的场景

```powershell
$env:ASR_ENABLED='true'
$env:ASR_PROVIDER='siliconflow'                  # 或 openai
$env:ASR_BASE_URL='https://api.siliconflow.cn/v1'
$env:ASR_API_KEY='sk-...'
$env:ASR_MODEL='FunAudioLLM/SenseVoiceSmall'     # openai 适配器填 whisper-1 之类
$env:ASR_LANGUAGE='zh'
# $env:ASR_CHUNK_SECONDS='60'
# $env:ASR_MAX_AUDIO_MB='100'
```

配置后必须重启应用。视频没有内嵌字幕时，后端用 ffmpeg 抽 16kHz 单声道 MP3
（48kbps，长音频体积更小），再调用该接口。阿里云/腾讯云原生 SDK 不是这个协议，
需要一个 OpenAI 兼容网关，或另写 `AsrPort` 适配器。

国内网络优先可以切硅基流动：

```powershell
$env:ASR_ENABLED='true'
$env:ASR_PROVIDER='siliconflow'
$env:ASR_BASE_URL='https://api.siliconflow.cn/v1'
$env:ASR_API_KEY='sk-...'
$env:ASR_MODEL='FunAudioLLM/SenseVoiceSmall'
$env:ASR_CHUNK_SECONDS='60'
```

硅基流动返回纯文本、不带句级 segments，所以适配器会把音频按 60 秒切片，
用切片起止时间生成证据时间戳，精度约 ±60 秒。需要逐句精确时间戳时，
使用 Whisper 系接口（`ASR_PROVIDER=openai`）或接阿里云/腾讯云原生时间戳。

### 不要把 Key 发到聊天里

```powershell
Copy-Item .env.example .env.local
# 编辑 .env.local，填入 ASR_API_KEY 等
.\scripts\run-local.ps1
```

`.env.local` 已被 gitignore；脚本只在本地进程里注入环境变量，不会把 Key 写进仓库。

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

- `mvn test`：51 项通过，0 失败，1 项可选 MQ 冒烟默认跳过
- 真 RocketMQ 冒烟：通过
- MySQL JSON artifact 集成测试：通过
- 手工端到端：任务 `DONE`，六个 artifact 落库，NOTE 首条要点 `GROUNDED / overlap=1.0`

## 回退

每个阶段打完都留了标签，按时间倒序：

```text
milestone-history-2026-10-19      任务历史面板
milestone-upload-2026-10-19       并发分片 + 整文件校验
milestone-local-run-2026-10-19    启动脚本自检 + 内存队列
milestone-asr-2026-10-19          SiliconFlow ASR
milestone-video-fix-2026-10-19    视频链路修复
milestone-video-2026-10-19        视频上传
milestone-frontend-2026-10-19     前端界面
milestone-t7-2026-10-19           MQ 与重试
```

```powershell
git fetch --tags
git tag                                  # 看全部标签
git switch -c rollback/frontend milestone-frontend-2026-10-19
```

把 `main` 强制拉回该点前请先备份分支，详见 [CHANGELOG.md](CHANGELOG.md)。

## Roadmap

- 画面 OCR：抓关键帧里的地址、命令、配置片段（ASR 只能拿到语音，URL 和型号全靠它）
- 术语注入：把标题/简介里的英文 token 作为 glossary 显式传给出稿阶段（当前靠标题隐式对齐，已验证够用）
- 多账户：users 表 + bcrypt + 会话，所有查询按 user_id 过滤（当前单用户，`user_id` 固定）

## 说明

视频内嵌字幕可直接解析；没有内嵌字幕时需要配置云 ASR。
B 站链接走公开 API，不再依赖 yt-dlp 的 412 链路；如果视频没有公开 CC 字幕，会明确提示配置 ASR。
YouTube 链接仍走 yt-dlp，必要时配置 `YTDLP_COOKIES_FILE`；画面 OCR 还没实现。
