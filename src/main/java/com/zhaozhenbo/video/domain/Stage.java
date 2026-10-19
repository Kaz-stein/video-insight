package com.zhaozhenbo.video.domain;

/**
 * 处理阶段。
 *
 * 用枚举而不是字符串常量：
 *   - 编译期就能挡住拼写错误（"SEGMENTING" 写错一个字母，字符串不会报错，枚举会）
 *   - 顺序本身就是业务规则（见 next()），不用在别处再维护一份顺序表
 */
public enum Stage {
    FETCHING,
    SEGMENTING,
    PLANNING,
    EXTRACTING,
    VERIFYING,
    COMPOSING;

    /** 下一个阶段；COMPOSING 之后没有了，返回 null */
    public Stage next() {
        Stage[] all = values();
        int idx = ordinal();
        return idx + 1 < all.length ? all[idx + 1] : null;
    }
}
