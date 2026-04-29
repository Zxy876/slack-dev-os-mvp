package com.asyncaiflow.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.asyncaiflow.web.Result;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Result<String> health() {
        return Result.ok("healthy", "ok");
    }
}
