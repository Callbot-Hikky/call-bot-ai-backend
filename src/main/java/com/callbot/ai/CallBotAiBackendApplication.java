package com.callbot.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class CallBotAiBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(CallBotAiBackendApplication.class, args);
	}

}
