package com.zhaozhenbo.video.application;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 B 站/YouTube 分享文案中提取视频链接，并做 B 站规范化。
 *
 * 分享文案常见形态：
 *   【标题】 https://b23.tv/xxxx
 *   【标题】 https://www.bilibili.com/video/BV...?share_source=copy_web&vd_source=...
 * 直接粘贴整段文字时，标题和追踪参数不应进入幂等指纹。
 */
public final class VideoUrlExtractor {

    private static final Pattern URL = Pattern.compile("https?://[^\\s，。；;、）)】\\]]+");
    private static final Pattern SHORT_URL =
            Pattern.compile("(?:https?://)?(?:www\\.)?b23\\.tv/[A-Za-z0-9]+");
    private static final Pattern BV = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Pattern TITLE = Pattern.compile("【([^】]{1,100})】");

    private VideoUrlExtractor() {
    }

    public static String extract(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("视频链接不能为空");
        }
        Matcher urls = URL.matcher(raw);
        while (urls.find()) {
            String candidate = clean(urls.group());
            String host = host(candidate);
            if (isAllowed(host)) {
                return canonical(candidate, host);
            }
        }

        Matcher shortUrl = SHORT_URL.matcher(raw);
        if (shortUrl.find()) {
            return "https://" + clean(shortUrl.group()).replaceFirst("^https?://", "");
        }

        Matcher bv = BV.matcher(raw);
        if (bv.find()) {
            return "https://www.bilibili.com/video/" + bv.group();
        }
        throw new IllegalArgumentException("没有识别到视频链接，请粘贴 B 站 / YouTube 分享链接或 BV 号");
    }

    /** 分享文案里的标题，作为用户没填标题时的兜底。 */
    public static String extractTitle(String raw) {
        if (raw == null) return null;
        Matcher matcher = TITLE.matcher(raw);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    private static String canonical(String url, String host) {
        if (host.equals("b23.tv")) {
            try {
                String path = URI.create(url).getPath();
                return "https://b23.tv/" + path.replace("/", "");
            } catch (Exception ignored) {
                return url;
            }
        }
        if (!host.endsWith("bilibili.com")) {
            return url;
        }
        Matcher bv = BV.matcher(url);
        if (!bv.find()) {
            return url; // b23 短链先保留，抓取阶段再解析
        }
        String result = "https://www.bilibili.com/video/" + bv.group();
        Matcher p = Pattern.compile("[?&]p=(\\d+)").matcher(url);
        if (p.find()) {
            result += "?p=" + p.group(1);
        }
        return result;
    }

    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isAllowed(String host) {
        return host.endsWith("bilibili.com") || host.equals("b23.tv")
                || host.endsWith("youtube.com") || host.equals("youtu.be");
    }

    private static String clean(String value) {
        return value.replaceAll("[]】」，。；;、）)]+$", "").strip();
    }
}
