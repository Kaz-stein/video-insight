# Project Rules

## Structure

```
video-insight/
├── src/main/java/com/zhaozhenbo/video/
│   ├── api/            控制器 + DTO（只做校验与转发）
│   ├── application/    PipelineService：阶段编排、降级决策、状态推进
│   ├── domain/         纯对象与不变量（不依赖 Spring）
│   ├── port/           外部能力接口
│   ├── adapter/        端口实现（字幕/ASR/LLM/持久化）
│   ├── pipeline/       各阶段实现
│   └── config/         线程池、超时、Redis、LLM 参数
├── src/main/resources/
│   ├── application.yml
│   └── schema.sql       v1 用 SQL 初始化表结构
├── src/test/java/...    单测 + 集成测试（用 FakeLlm，不烧 token）
├── tools/eval/          评测集与跑分脚本
├── PRD.md
├── TECH_DESIGN.md
└── TASKS.md
```

## Commands

本机没有 Maven on PATH，用 IDEA 自带的那份；JAVA_HOME 指向 JetBrains Runtime：

```powershell
$env:JAVA_HOME='D:\IntelliJ IDEA 2026.1\jbr'
$env:Path = "C:\Windows\System32;C:\Windows;" + $env:Path
& 'D:\IntelliJ IDEA 2026.1\plugins\maven\lib\maven3\bin\mvn.cmd' -B test
```

- 编译打包：同上，把 `test` 换成 `-DskipTests package`
- 运行：`java -jar target/video-insight-0.0.1-SNAPSHOT.jar`
- 本地演示不接真实模型：`$env:LLM_PROVIDER='fake'`
- 真实模型（OpenAI 兼容）：`$env:LLM_PROVIDER='http'`，再配
  `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL`（DeepSeek、Qwen、GLM 都适用）
- 依赖镜像：已配 `~/.m2/settings.xml` 走阿里云（本机 Maven 首次拉依赖很慢）
- RocketMQ（两个进程，必须先起再启动应用）：
  `$env:ROCKETMQ_HOME='D:\Code\tools\rmq\rocketmq-all-5.5.1-bin-release'`，
  然后分别跑 `bin\mqnamesrv.cmd -n 127.0.0.1:9876` 与
  `bin\mqbroker.cmd -n 127.0.0.1:9876 -c conf\broker-local.conf`
- 真 MQ 冒烟（可选，需 broker 已启动）：
  先 `mqadmin updateTopic -n 127.0.0.1:9876 -c DefaultCluster -t video-task-smoke`，
  再 `$env:MQ_SMOKE='true'; mvn -Dtest=RocketMqSmokeTest test`
- **RocketMQ 5.5.1 的磁盘水位硬上限是 90%**：`CleanCommitLogService` 把
  `diskSpaceWarningLevelRatio` 夹在 0.90，配置调高无效；broker 所在盘超过 90% 会进入只读，
  表现为发送时报 `service not available ... disk is full`
- 本地 broker 已把 `mappedFileSizeCommitLog` 降到 64MB，并用
  `maxHashSlotNum/maxIndexNum` 把 index 文件从 400MB 降到约 44MB，
  避免默认预分配把接近 90% 的磁盘顶穿

## Conventions

- **Java 25 / Spring Boot 4.1**，不引入前端框架（前端单独目录，原生实现）
- 分层硬约束：`domain` 不依赖 Spring；`application` 不写 SQL、不拼 prompt；
  `port` 只有接口；只有 `adapter` 可以碰 HTTP/文件/DB 细节
- **所有 LLM 调用必须走 `LlmPort`**：测试一律用 `FakeLlmAdapter`，禁止在测试里打真实模型
- **数据库是事实源，MQ 只做投递**：先落库再发消息；发送失败靠补偿任务补发，不许"只发消息不落库"
- 消费必须幂等：用 `UPDATE ... WHERE status IN (...)` 的 affected rows 判断，禁止"先查后改"
- **改 prompt / 改切段算法必须 bump 版本号**（`note.vN` / `pipeline.vN`），否则幂等键不变、
  会命中旧产物，线上生成结果与代码不一致
- `params_hash` 的序列化必须字段顺序稳定（record / TreeMap），禁止直接序列化 `HashMap`
- `video_artifact.payload` 里**禁止**存原始文件名、user_id 等身份信息（要跨用户复用）
- 时间统一用**毫秒 long**（`startMs/endMs/durationMs`），不混用 `Duration` 与字符串
- 文本比较前必须先过 `TextNormalizer`（去空白/标点/全半角/小写），否则中文比对不可靠
- 表结构改动同步 `schema.sql` 与 `TECH_DESIGN.md` 的数据模型
- 所有对外错误消息必须是**人话 + 可定位**（哪个阶段、为什么），不许直接抛异常栈

## Boundaries

- **不要**把视频/音频文件提交进仓库（`.gitignore` 里已有 `data/` 与 `*.mp4`）
- **不要**在测试或 CI 里调用真实 LLM（会烧钱且结果不确定）
- **不要**在没有验证的情况下往简历上写 MQ / Docker / MinIO：v1 没做就是没做
- **不要**把"没有依据的结论"当事实写进正文——这是产品的底线，不是可选项
- 不要引入前端构建链（Vite/React）到主工程，前端保持原生 HTML/CSS/JS

## Verification

一次改动算完成，必须同时满足：

1. `mvn test` 全绿（含证据校验、降级决策、状态机推进的用例）
2. 端到端测试通过：提交 → 轮询 → 拿到笔记，且**伪造引用的用例被剔除**
3. 新增/修改 prompt 时，`tools/eval` 的指标不能回退（引用可回溯率不下降）
