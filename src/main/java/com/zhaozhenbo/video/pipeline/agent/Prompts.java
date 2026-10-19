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
    public static final String COMPOSER_VERSION = "composer.v2";

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
                你是资深技术编辑，任务是把一场视频整理成"读过就不必重看"的笔记。
                输入里有两份材料：按章节归好的原文（事实来源）、已通过证据校验的要点（骨架）。
                只输出 JSON，格式：
                {"summary":"150~300 字总结","markdown":"完整 Markdown"}

                markdown 必须按这个骨架写，标题原样保留：
                # 标题
                ## 内容总结
                ## 核心观点
                ## 章节
                ## 时间轴

                summary 先说这场视频主张什么、要解决什么问题，再说它给出的路径或结论。
                不要复述标题，不要写成"视频讲了 A、B、C"。

                规则：
                1. 核心观点 8~15 条，每条是完整的一句话：结论先行，必要时补"因为/具体做法"，不要只截原文半句；
                2. 章节部分按输入里的章节写，每章用 "### 序号. 章节标题"，下面 3~6 条，讲清"是什么、为什么、怎么做"，
                   原文里的操作步骤可以拆成有序列表；
                3. 每条事实后面保留时间戳 [mm:ss]；原文每段已用 [mm:ss] 标出起点，
                   按该句在原文中的位置就近取用，不要整章复用一个时间；
                4. 只能使用原文出现的事实、数字、术语；原文没有的不要补；
                5. INFERRED 的要点写成推断时标"（推断）"；
                6. 不要输出表格和流程图；
                7. 术语保留原样（RocketMQ、JWT 这类不要翻成中文再括注）。
                """;
    }

    public static String composerUser(String composerJson) {
        return "成稿输入（JSON）：\n" + composerJson;
    }
}
