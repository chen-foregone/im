package com.bx.imsimulator;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.bx.imsimulator.mapper")
public class IMSimulatorApp {

    public static void main(String[] args) {
        SpringApplication.run(IMSimulatorApp.class, args);
    }
}
