package com.zhaozhenbo.video;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VideoInsightApplication {

	public static void main(String[] args) {
		SpringApplication.run(VideoInsightApplication.class, args);
	}

}
