package com.gupify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GupifyApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(GupifyApiApplication.class, args);
    }
}