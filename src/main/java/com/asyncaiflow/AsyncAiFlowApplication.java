package com.asyncaiflow;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.asyncaiflow.mapper")
@EnableScheduling
public class AsyncAiFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(AsyncAiFlowApplication.class, args);
    }
}