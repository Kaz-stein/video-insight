package com.zhaozhenbo.video.adapter.caption;

import com.zhaozhenbo.video.application.TaskContext;
import com.zhaozhenbo.video.domain.CaptionCue;

import java.util.List;
import java.util.Locale;

/** 根据任务来源选择字幕获取方式。 */
public class DefaultCaptionResolver {

    private final LocalCaptionSource local;
    private final VideoCaptionSource video;
    private final LinkCaptionSource link;

    public DefaultCaptionResolver(LocalCaptionSource local,
                                  VideoCaptionSource video,
                                  LinkCaptionSource link) {
        this.local = local;
        this.video = video;
        this.link = link;
    }

    public List<CaptionCue> resolve(TaskContext context) {
        String type = context.sourceType() == null ? "" : context.sourceType().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "VIDEO" -> video.load(context.sourceRef());
            case "BILIBILI", "LINK" -> link.load(context.sourceRef());
            default -> local.load(context.sourceRef());
        };
    }
}
