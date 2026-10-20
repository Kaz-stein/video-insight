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


    public static String composerUser(String composerJson) {
        return "成稿输入（JSON）：\n" + composerJson;
    }
}
