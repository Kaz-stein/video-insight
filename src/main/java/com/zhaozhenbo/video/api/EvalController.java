package com.zhaozhenbo.video.api;

import com.zhaozhenbo.video.api.dto.EvalResponse;
import com.zhaozhenbo.video.application.EvalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EvalController {

    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    @GetMapping("/api/eval")
    public EvalResponse eval() {
        return evalService.evaluate();
    }
}
