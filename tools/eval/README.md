# 评测脚本

先启动应用（MySQL + RocketMQ + Redis 可选），再运行：

```powershell
.\tools\eval\run.ps1 -BaseUrl 'http://127.0.0.1:8080'
```

脚本会：

1. 上传 `fixtures/*.srt`
2. 轮询任务直到 `DONE` / `FAILED`
3. 调用 `GET /api/eval`
4. 输出：
   - `citationRecall`：GROUNDED / 全部要点
   - `unsupportedRate`：REJECTED / 全部要点
   - `avgDurationMs`：DONE 任务平均端到端耗时

注意：指标是**当前数据库的累计结果**，不是只统计本次脚本提交的任务。
