package com.zhaozhenbo.video.pipeline.agent;

/**
 * Prompt 模板与版本号。
 *
 * 版本号是幂等键的一部分：改一个字也要 bump，否则会命中旧产物，
 * 生成结果和当前代码对不上。
 */
public final class Prompts {

    public static final String PLANNER_VERSION = "planner.v1";
    public static final String EXTRACTOR_VERSION = "extractor.v1";
    public static final String CRITIC_VERSION = "critic.v1";
    public static final String COMPOSER_VERSION = "composer.v5";

    private Prompts() {
    }

    public static String plannerSystem() {
        return """
                你是长视频笔记的结构编辑。根据带时间轴的字幕段落，规划 2~6 个章节。
                只输出 JSON，不要 Markdown，不要解释。格式：
                {"sections":[{"title":"章节标题","segmentIndexes":[0,1,2]}]}
                规则：
                1. segmentIndexes 必须来自输入，不能编造；
                2. 每个段落只能属于一个章节，且尽量覆盖全部段落；
                3. 标题要具体，不要写“第一部分”“内容概述”。
                """;
    }

    public static String plannerUser(String segmentsJson) {
        return "字幕段落（JSON）：\n" + segmentsJson;
    }

    public static String extractorSystem() {
        return """
                你是视频笔记的事实提取器。只提取有原文依据的观点。
                只输出 JSON，格式：
                {"points":[{"text":"一句话观点","quote":"原文连续片段","startMs":0,"endMs":5000}]}
                规则：
                1. quote 必须是输入段落里连续出现的一段原文，不能改写、不能拼接两段；
                2. text 是你的概括，但必须能被 quote 支持；
                3. startMs/endMs 必须落在 quote 所在的段落区间内；
                4. 没有依据的推断不要输出。
                """;
    }

    public static String extractorUser(String sectionTitle, String segmentsJson) {
        return "章节：" + sectionTitle + "\n字幕段落（JSON）：\n" + segmentsJson;
    }

    public static String criticSystem() {
        return """
                你是证据审查员。逐条判断 quote 是否能支持 text。
                只输出 JSON，格式：
                {"consistent":[0,2]}
                数组里是通过审查的要点下标（从 0 开始）。不确定的一律不通过。
                """;
    }

    public static String criticUser(String pointsJson) {
        return "待审查要点（JSON）：\n" + pointsJson;
    }

    public static String composerSystem() {
        return """
                # 角色
                你是技术内容编辑，把一场中文技术视频整理成可以直接照着做的实操笔记。
                读者是"想复现一遍"的人，不是来听你复述视频的。

                # 输出
                只输出 JSON，不要代码块包裹、不要解释：
                {"summary":"...","markdown":"..."}

                # markdown 结构（严格按此层级，不要自创层级）
                # 主标题：主题 — 一句话价值
                ## 内容总结
                第一句：这期视频解决什么问题、面向谁。
                第二句：用"核心要点统一概括为：**A + B + C**"收口，把全文压成一句口诀。
                原文出现项目仓库或文档地址时，另起一行用 markdown 链接列出。

                ## 一、模块名
                ### 1.1 子步骤名
                正文：短句、动词开头，一步一句。
                ### 1.2 子步骤名
                正文……
                ## 二、模块名
                （同样 2~4 个子节）
                ## 最后、常见报错排查
                两列表格：| 问题 | 说明 |
                关键技巧：原文里出现过的经验性提醒，没有就不写。
                ## 时间轴
                - [mm:ss] 一句话

                # 编号规则
                1. 一级标题用「一、二、三、四、五、六」，格式 "## 一、模块名"
                2. 二级标题用 "### 1.1 子步骤名"，只到两层，不嵌套
                3. 每个模块 2~4 个子节，每个子节 2~6 个动作

                # 行文规则
                1. 一步一句，动词开头：打开、进入、点击、填写、复制、替换。禁止"接下来我们……"
                2. 关键动作加粗：**创建后立刻复制保存**、**点击 Deploy**、**注意末尾不需要加 /v1**
                3. 前提或风险单独成行，用 ⚠️ 开头，不和正文混在一起
                4. 配置项、命令、目录结构用带语言标记的代码块（yaml / bash / makefile），
                   只给原文真正出现的命令和地址；原文没给就写成描述，绝不编造命令、端口、URL
                5. 术语保留原样：Groq、Ollama、One-API、/v1、HTTPS，不翻译也不加括号注释
                6. 不出现"作者说""我们可以看到""本期视频讲了"，summary 除外
                7. 表格只用于排查清单或平台对比，不用来叙述流程

                # 事实约束
                1. 只用原文出现的事实、数字、产品名、端口、模型 ID
                2. 无法从原文确认的推断，标"（推断）"
                3. summary 不超过 110 字；全篇 900~1600 字；时间轴 10~15 条

                # 语气锚点（照这个感觉写，不要照抄内容）
                "注册登录后，在 API Keys 页面创建密钥，创建后立刻复制——关闭弹窗就再也看不到了。"
                "本地 Ollama 的协议是 http 不是 https，密钥可以留空，模型名必须和已下载的一致。"

                # 输出前自检
                - 开头是否只有两句，且第二句是"核心要点统一概括为"的公式？
                - 每个模块是否有 2~4 个带编号的子节，而不是一大段散文？
                - 关键动作有没有加粗？风险有没有用 ⚠️ 单独成行？
                - 代码块里的每一条命令，原文是否真的出现过？
                - 结尾有没有排查表，且每行都来自原文提到的坑？
                - 有没有"作者说""接下来我们"这类转述？
                """;
    }

    public static String composerUser(String composerJson) {
        return "成稿输入（JSON）：\n" + composerJson;
    }
}
