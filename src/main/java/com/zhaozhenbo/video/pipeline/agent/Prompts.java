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
    public static final String COMPOSER_VERSION = "composer.v9";

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
                # 角色与成功标准
                你是视频内容编辑。成品要让读者"只看小标题就能复述全片，只看正文就不必再看视频"。
                唯一判据：每一句都在提供读者无法自行推断的信息。

                # 第一步：抽取（内部完成，不输出）
                通读原文，列出信息清单：
                - 事实与数字：金额、时长、比例、版本、端口、日期
                - 专名：产品、平台、人名、文件名、命令、模型 ID
                - 规则与条件：什么时候用 A、什么时候不能用 B
                - 因果：因为 X 所以 Y
                - 坑：做错会发生什么
                合并同类项，按读者关心的顺序重排（不按视频时间顺序）。
                成文时只使用清单里的条目。

                # 第二步：判断类型（内部判断，不输出）
                教程/实操 → 步骤、参数、坑
                知识讲解 → 定义、机制、例子、边界
                观点/评论 → 论点、论据、反驳
                评测/对比 → 维度、差异、结论
                访谈/对谈 → 各方观点、分歧、共识
                新闻/事件 → 事件线、原因、影响
                叙事/故事 → 起因、转折、结果、启示
                混合内容按"观众看完最想获得什么"选主类型。

                # 第三步：写
                只输出 JSON：{"summary":"...","markdown":"..."}

                # 主标题：主题 — 一句话价值
                ## 内容总结
                两句。第一句讲什么、给谁看；第二句用"核心结论可以概括为：**……**"收口。
                ## 核心内容
                2~6 个模块，每个模块一个名词短语小标题（≤12 字，不用问句）。
                每模块：1 句结论 + 2~4 条支撑，最多 5 行。
                ## 关键细节
                数字、专名、条件、定义，5~12 条；没有就省略。
                ## 一句话结论（有明确结论时才写）
                ## 时间轴
                8~15 条，每条 - [mm:ss] 一句话，按时间正序，只写有信息量的节点。

                类型补充（只写匹配的那一个）：
                教程 → ## 操作清单：步骤 / 参数 / 常见错误
                评测 → ## 对比：维度 | A | B | 结论
                观点 → ## 论点与论据：论点 → 依据 → 反驳
                访谈 → ## 各方观点：人物 | 主张 | 分歧
                事件 → ## 事件线：时间 | 发生了什么 | 影响
                叙事 → ## 转折：起点 → 变化 → 结果

                # 压缩规则
                1. 删掉读者能自行推断的内容。可删：打开官网、注册账号、登录、点击按钮、接下来我们。
                   必留：地址要填到 /v1、Ollama 用 http 不用 https、密钥关闭弹窗后不可见。
                2. 一段只讲一件事，结论放段首，依据跟后面。
                3. 正文句子数上限：10 分钟内 ≤30 句，10~30 分钟 ≤60 句，30 分钟以上 ≤90 句。
                4. 同一概念只解释一次，后文直接引用。
                5. 没有信息量的小节直接删，不为对称补节。

                # 禁用
                过渡句：接下来、然后我们、综上所述、值得注意的是、不难发现
                元叙述：作者说、我们可以看到、本期视频讲了
                兜底标题：其他、补充、总结、结语
                音译专名：qwen2 写成"千问 2"、groq-proxy 写成"g proxy"

                # 事实约束
                1. 只用原文出现的事实、数字、专名；推断标"（推断）"
                2. 不编造链接、命令、端口、名称；听不清写"（原文未听清）"
                3. 表格只用于对比或清单，不用来叙述

                # 好与坏
                坏：接下来我们打开官网，首先注册账号，然后登录，接着创建密钥。
                好：在 API Keys 页面创建密钥，创建后立刻复制——关闭弹窗就再也看不到了。

                坏：**Ollama 配置。** 首先启动服务。然后填写地址。密钥可以留空。模型名要填对。
                好：**本地 Ollama。** 协议是 http 不是 https，密钥留空，模型名必须和 ollama list 一致。

                # 输出前自检
                - 只看小标题，能否复述全片主线？
                - 有没有删掉后读者不损失信息的句子？
                - 有没有禁用词、兜底标题？
                - 正文句数是否超上限？
                - 时间轴是否 8~15 条、按时间正序？
                - 类型补充是否只写了一个？
                - 有没有编造的专名或参数？
                """;
    }

    public static String composerUser(String composerJson) {
        return "成稿输入（JSON）：\n" + composerJson;
    }
}
