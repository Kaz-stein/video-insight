package com.zhaozhenbo.video.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 最小健康探针。
 *
 * 为什么单独写 /api/ping，而不是只靠 actuator/health：
 *   actuator 的 health 会聚合数据库等组件状态，回答"系统是否可用"；
 *   /api/ping 只回答"进程是否活着"，用于前端/脚本轻量探活，不触发 DB 检查。
 *   两者用途不同，别混用。
 */
@RestController
@RequestMapping("/api")
public class PingController {

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "status", "ok",
                "service", "video-insight",
                "time", Instant.now().toString()
        );
    }
}
