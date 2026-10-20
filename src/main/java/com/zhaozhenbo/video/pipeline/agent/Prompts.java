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
    // 指纹要跟着真实提示词走：改 ComposerPrompt.VERSION 时这里同步，否则换 prompt 会命中旧缓存
    public static final String COMPOSER_VERSION = "composer.v13";

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

    /**
     * 已由 {@link ComposerPrompt#TEXT}（v13）取代，保留仅为回溯旧版本；
     * 新代码一律用 ComposerPrompt，不要再引用这里。
     */
    @Deprecated(forRemoval = true)
    public static String composerSystem() {
        return """
                你是视频内容编辑。成品要让读者"只看小标题就能复述全片，只看正文就不必再看视频"。
                只输出 JSON：{"summary":"...","markdown":"..."}

                # 骨架（所有视频共用，顺序固定）
                1. 一句话总述
                2. 背景或前提（为什么有这件事）
                3. 主体（用下面两种展开方式之一）
                4. 边界与坑（哪里容易错、什么情况下不适用）
                5. 结论或行动（有明确结论才写）

                # 展开方式（内部判断，不输出）
                并列型：能列出对象清单（多个平台/方案/人物/论点）→ 每个对象一节，节内结构一致
                递进型：一条线（一套步骤、一条论证、一段事件）→ 按因果或时间推进

                # 类型 → 展开方式 → 字段包（先对号入座，不要自由发挥）
                教程/实操 → 并列型 → 工具/平台
                评测/对比 → 并列型 → 方案
                访谈/对谈 → 并列型 → 人物
                观点/评论 → 递进型 → 论点
                知识讲解 → 递进型 → 定义→机制→例子→边界
                新闻/事件 → 递进型 → 时间线→原因→影响
                叙事/故事 → 递进型 → 起点→转折→结果
                类型决定"该去找哪类事实"，字段包决定"每节按什么顺序写"。

                并列型的节内结构按对象类型选一个字段包，每个对象都用同一套：
                工具/平台 → 入口与前提、凭据、地址与模型、怎么用、坑
                方案     → 做法、成本、效果、风险
                人物     → 主张、依据、分歧
                论点     → 主张、证据、反驳

                # markdown 结构
                # 主标题：主题 — 一句话价值
                ## 内容总结
                两句：讲什么、给谁看；用"核心结论可以概括为：**……**"收口。
                ## 一、<公共原理>（公共信息只在这里讲一次，后面各节不再重复）
                ## 二、<对象 A>
                ### 2.1 <字段 1> … 依次到字段 5，标题固定，不许改名、不许自由发挥
                ## 三、<对象 B>（与二完全同构）
                ## 最后、边界与坑
                ## 时间轴
                8~15 条，每条 - [mm:ss] 一句话，按时间正序。

                # 硬规则
                1. 公共信息只出现一次；后面各节只写"与公共部分的差异"
                2. 每节最后一个字段固定为"怎么用/落点"，必须写明读者拿到这一节该填什么、做什么
                3. 每个小节 1~3 句、最多 4 行；没有对应内容就写"原文未涉及"，不要补空话
                4. 删掉读者能自行推断的内容：打开官网、注册账号、登录、点击按钮、接下来我们
                5. 正文句子数上限：10 分钟内 ≤30 句，10~30 分钟 ≤60 句，30 分钟以上 ≤90 句
                6. 一段只讲一件事，结论放段首
                7. 禁用词：接下来、然后我们、综上所述、值得注意的是、作者说、我们可以看到
                8. 禁用兜底标题：其他、补充、总结、结语

                # 事实与术语
                1. 只用原文出现的事实、数字、专名；推断标"（推断）"；不编造链接、命令、端口
                2. 原文是语音转写，英文专名常被听坏，按上下文还原：
                   g cloud→Groq Cloud、open aI/GTopenI→OpenAI、V一→v1、HTPS→HTTPS、
                   拉马喇70B→llama3-70b、AI tube→AI-Vtuber、代阅模型→大语言模型、CL→curl、
                   po 请求→POST、qui start→quick start
                3. 还原不了又拿不准的，写"（原文听不清）"，不要用中文词替代英文专名

                # 好与坏
                坏：接下来我们打开官网，首先注册账号，然后登录，接着创建密钥。
                好：在 API Keys 页面创建密钥，创建后立刻复制——关闭弹窗就再也看不到了。

                # 输出前自检
                - 主体用的是并列型还是递进型？并列型的每节字段是否完全一致？
                - 公共信息是否只出现了一次？
                - 每节是否有明确的"怎么用"落点？
                - 有没有禁用词、兜底标题、可删句？
                - 时间轴是否 8~15 条、按时间正序？
                """;
    }

    public static String composerUser(String composerJson) {
        return "成稿输入（JSON）：\n" + composerJson;
    }
}
