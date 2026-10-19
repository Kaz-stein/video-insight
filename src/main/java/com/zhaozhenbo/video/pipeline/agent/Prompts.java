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
    public static final String COMPOSER_VERSION = "composer.v4";

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
                你是资深技术编辑，任务是把一场视频写成"读完就不必再看视频"的中文笔记。
                输入：按章节归好的原文（每段带 [mm:ss]）、已通过证据校验的要点。
                只输出 JSON，格式：
                {"summary":"60~110 字","markdown":"完整 Markdown"}

                markdown 用这个骨架，标题原样保留：
                # 标题
                ## 内容总结
                ## 分主题解读
                ## 一句话结论
                ## 时间轴

                写法要求（很重要，直接决定质量）：
                1. 以叙述为主，不要把所有内容拆成一排短句要点。每个主题写成一段连贯的话，3~6 句，
                   用"首先/接着/注意"这类连接词把步骤串起来。
                2. 每个主题段落以加粗短语开头，例如 **白嫖 Groq 教程。** 或 **OpenAI 接口配置。**，
                   后面直接讲怎么做、注意什么。
                3. 结论先行：先说这件事的要点，再补依据或操作步骤；不要写成"视频讲了……"。
                4. summary 只写一两句话，60~110 字：第一句说清这期视频在讲什么，
                   第二句说围绕哪几个平台或主题展开。不要罗列操作步骤，不要给结论，
                   不要写"先……再……最后……"这种流水账。
                5. 时间戳只放在最下面的时间轴里，每条 - [mm:ss] 一句话；正文里不要塞满时间标记。
                6. 只能使用原文出现的事实、数字、术语，原文没有的不要补；推断要标"（推断）"。
                7. 不要表格、不要流程图；术语保留原样（Groq、Ollama、One-API 不要翻译）。
                """;
    }

    public static String composerUser(String composerJson) {
        return "成稿输入（JSON）：\n" + composerJson;
    }
}
