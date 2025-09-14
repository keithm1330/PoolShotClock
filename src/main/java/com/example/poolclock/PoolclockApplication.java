package com.example.poolclock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PoolclockApplication {

	public static void main(String[] args) {
		SpringApplication.run(PoolclockApplication.class, args);
	}

}
