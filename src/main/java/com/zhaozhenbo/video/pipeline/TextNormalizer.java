package com.zhaozhenbo.video.pipeline;

import java.util.regex.Pattern;
import java.util.Locale;

/**
 * 字幕文本清洗：不同平台导出的字幕夹杂 HTML 标签、ASS 样式标记、全角空格。
 *
 * 规范化必须发生在“算哈希 / 算重合度”之前，否则同一句话因为一个 &nbsp;
 * 就会算出不同的指纹，或者明明引用正确却被判定为不重合。
 */
public final class TextNormalizer {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]{0,200}>");
    private static final Pattern ASS_TAG = Pattern.compile("\\{\\\\[^}]{0,100}}");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0\\u3000]+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private TextNormalizer() {
    }

    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\uFEFF", "");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        text = ASS_TAG.matcher(text).replaceAll(" ");
        text = text.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&amp;", "&");
        return normalizeWhitespace(text);
    }

    public static String normalizeWhitespace(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ").strip();
    }

    /**
     * 证据比对专用：去掉所有空白、标点、符号，只留下字母/数字/汉字，并转小写。
     * 这样 "Java 的线程池。" 与 "java的线程池" 会被视为同一串字符。
     */
    public static String normalizeForCompare(String text) {
        if (text == null) {
            return "";
        }
        return NON_WORD.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /**
     * 拼接两段字幕。
     *
     * 英文之间要留空格，中文之间不能留空格；否则 "Hello" + "world" 会粘成
     * "Helloworld"，而 "你好" + "世界" 会变成 "你好 世界"（影响 3-gram 重合度）。
     */
    public static String join(String left, String right) {
        if (left == null || left.isEmpty()) {
            return right == null ? "" : right;
        }
        if (right == null || right.isEmpty()) {
            return left;
        }
        char l = left.charAt(left.length() - 1);
        char r = right.charAt(0);
        boolean needSpace = (isAsciiWord(l) && isAsciiWord(r))
                || (isAsciiPunctuation(l) && isAsciiWord(r));
        return needSpace ? left + " " + right : left + right;
    }

    private static boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static boolean isAsciiPunctuation(char c) {
        return ".,!?;:".indexOf(c) >= 0;
    }
}
